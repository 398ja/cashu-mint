package xyz.tcheeric.cashu.mint.admin.rest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.LifecycleCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort.VaultKeySet;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.DenominationResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.KeySetResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.MintDetailResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.admin.rest.presenter.LifecycleSummaryApiPresenter;

/**
 * Delegates lifecycle operations to the core {@link ManageMintLifecycleUseCase} interactor
 * and converts responses into API payloads via the presenter.
 */
@Service
public class AdminLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AdminLifecycleService.class);

    private static final String DEFAULT_VERSION_TAG = "v1";

    private final ManageMintLifecycleUseCase lifecycleUseCase;
    private final MintRepository mintRepository;
    private final LifecycleSummaryPresenter summaryPresenter;
    private final LifecycleSummaryApiPresenter apiPresenter;
    private final OperatorIdentity operatorIdentity;
    private final KeySetInventoryPort keySetInventory;

    public AdminLifecycleService(final ManageMintLifecycleUseCase lifecycleUseCase,
                                 final MintRepository mintRepository,
                                 final LifecycleSummaryPresenter summaryPresenter,
                                 final LifecycleSummaryApiPresenter apiPresenter,
                                 final OperatorIdentity operatorIdentity,
                                 final KeySetInventoryPort keySetInventory) {
        this.operatorIdentity = Objects.requireNonNull(operatorIdentity, "operatorIdentity");
        this.lifecycleUseCase = Objects.requireNonNull(lifecycleUseCase, "lifecycleUseCase");
        this.mintRepository = Objects.requireNonNull(mintRepository, "mintRepository");
        this.summaryPresenter = Objects.requireNonNull(summaryPresenter, "summaryPresenter");
        this.apiPresenter = Objects.requireNonNull(apiPresenter, "apiPresenter");
        this.keySetInventory = Objects.requireNonNull(keySetInventory, "keySetInventory");
    }

    public PagedResponse<MintDetailResponse> listMints(final String state, final String q,
                                                        final int page, final int size) {
        List<MintAggregate> mints = mintRepository.findAll();
        if (state != null && !state.isBlank()) {
            mints = mints.stream()
                    .filter(m -> m.lifecycleState().value().name().equalsIgnoreCase(state))
                    .toList();
        }
        if (q != null && !q.isBlank()) {
            final String search = q.toLowerCase();
            mints = mints.stream()
                    .filter(m -> m.mintId().asString().toLowerCase().contains(search))
                    .toList();
        }
        final List<MintDetailResponse> items = mints.stream().map(this::toDetailResponse).toList();
        return PagedResponse.of(items, page, size);
    }

    public MintDetailResponse getMint(final String mintId) {
        final MintAggregate aggregate = mintRepository.findById(MintId.fromString(mintId))
                .orElseThrow(() -> new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found",
                        "Mint not found: " + mintId));
        return toDetailResponse(aggregate);
    }

    /**
     * The keysets the shared vault holds for a mint, signing keyset first and the archived
     * ones newest-first behind it — the order that answers "did my rotation actuate" in one
     * glance. Ordering lives here rather than in the browser so it is stated once.
     *
     * <p>A mint with nothing provisioned is an empty list; a vault that cannot be read is an
     * error. Reporting the second as the first would tell an operator that a rotation
     * destroyed key material that is in fact still there.
     */
    public PagedResponse<KeySetResponse> listKeySets(final String mintId, final int page, final int size) {
        final MintId id = parseMintId(mintId);
        final List<VaultKeySet> keySets = readFromVault(mintId,
                () -> keySetInventory.listByMint(id.value()));
        final List<KeySetResponse> items = keySets.stream()
                .sorted(Comparator.comparing((VaultKeySet k) -> k.archived())
                        .thenComparing(VaultKeySet::createdAt, Comparator.reverseOrder()))
                .map(AdminLifecycleService::toKeySetResponse)
                .toList();
        return PagedResponse.of(items, page, size);
    }

    /**
     * The denominations of one of a mint's keysets, ascending by amount.
     *
     * <p>Not paged: a keyset holds one key per power of two it serves, so the whole set fits
     * an answer. A keyset the mint does not hold is an empty list rather than a 404 — the page
     * asks about a keyset it just listed, so absence means the vault changed underneath it,
     * which reads better as "no denominations" than as a dead URL.
     */
    public List<DenominationResponse> listDenominations(final String mintId, final String keySetId) {
        final MintId id = parseMintId(mintId);
        return readFromVault(mintId, () -> keySetInventory.listDenominations(id.value(), keySetId)).stream()
                .map(d -> new DenominationResponse(d.amount(), d.vaultPath()))
                .toList();
    }

    private static MintId parseMintId(final String mintId) {
        try {
            return MintId.fromString(mintId);
        } catch (final IllegalArgumentException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_mint_id", e.getMessage());
        }
    }

    /**
     * Runs a vault read, turning any failure into one 502 the Operator can act on.
     *
     * <p>Logged with its cause: the operator is told the vault is unreachable, which is all
     * they can act on, but a bug in the adapter would otherwise leave no trace.
     */
    private static <T> T readFromVault(final String mintId, final Supplier<T> read) {
        try {
            return read.get();
        } catch (final RuntimeException e) {
            log.warn("Could not read keysets for mint {} from the vault", mintId, e);
            throw new AdminServiceException(HttpStatus.BAD_GATEWAY, "vault_unavailable",
                    "Could not read keysets from the vault: " + e.getMessage());
        }
    }

    private static KeySetResponse toKeySetResponse(final VaultKeySet keySet) {
        return new KeySetResponse(keySet.keySetId(), keySet.unit(),
                keySet.archived() ? KeySetResponse.ARCHIVED : KeySetResponse.SIGNING,
                keySet.createdAt(), keySet.inputFeePpk());
    }

    private MintDetailResponse toDetailResponse(final MintAggregate aggregate) {
        return new MintDetailResponse(
                aggregate.mintId().asString(),
                aggregate.lifecycleState().value().name(),
                aggregate.configurationSet().revisionId().value(),
                aggregate.configurationSet().parameters(),
                aggregate.configurationSet().auditMetadata().action(),
                aggregate.auditMetadata().actor(),
                aggregate.auditMetadata().action(),
                aggregate.auditMetadata().timestamp());
    }

    public LifecycleActionResponse createMint(final CreateMintRequest request) {
        Objects.requireNonNull(request, "request");
        final String versionTag = extractVersionTag(request.configuration(), DEFAULT_VERSION_TAG);
        final java.util.Map<String, String> configParams = extractConfigurationParameters(request.configuration());
        final ManageMintLifecycleRequest useCaseRequest = new ManageMintLifecycleRequest(
            request.mintId(),
            operatorIdentity.currentOperatorId(),
            LifecycleCommand.CREATE,
            versionTag,
            null,
            null,
            configParams
        );
        try {
            final ManageMintLifecycleResponse response = lifecycleUseCase.handle(useCaseRequest);
            final LifecycleSummary summary = summaryPresenter.present(LifecycleAction.CREATE,
                response.mintId(), null, response.lifecycleState().name(), response.versionTag(), true);
            return apiPresenter.present(summary);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                throw new AdminServiceException(HttpStatus.CONFLICT, "mint_already_exists", e.getMessage());
            }
            throw mapDomainException(e);
        } catch (IllegalArgumentException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
        }
    }

    public LifecycleActionResponse updateMint(final String mintId, final UpdateMintRequest request) {
        Objects.requireNonNull(request, "request");
        final String versionTag = extractVersionTag(request.configuration(), request.revisionId());
        final ManageMintLifecycleRequest useCaseRequest = new ManageMintLifecycleRequest(
            mintId,
            operatorIdentity.currentOperatorId(),
            LifecycleCommand.UPDATE_CONFIGURATION,
            versionTag,
            null,
            null
        );
        try {
            final ManageMintLifecycleResponse response = lifecycleUseCase.handle(useCaseRequest);
            final LifecycleSummary summary = summaryPresenter.present(LifecycleAction.UPDATE,
                response.mintId(), response.lifecycleState().name(), response.lifecycleState().name(),
                response.versionTag(), true);
            return apiPresenter.present(summary);
        } catch (IllegalStateException e) {
            throw mapDomainException(e);
        } catch (IllegalArgumentException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
        }
    }

    public LifecycleActionResponse pauseMint(final String mintId, final LifecycleChangeRequest request) {
        return executeTransition(mintId, request, LifecycleCommand.PAUSE, LifecycleAction.PAUSE);
    }

    public LifecycleActionResponse resumeMint(final String mintId, final LifecycleChangeRequest request) {
        return executeTransition(mintId, request, LifecycleCommand.RESUME, LifecycleAction.RESUME);
    }

    public LifecycleActionResponse retireMint(final String mintId, final LifecycleChangeRequest request) {
        return executeTransition(mintId, request, LifecycleCommand.RETIRE, LifecycleAction.RETIRE);
    }

    private LifecycleActionResponse executeTransition(final String mintId,
                                                       final LifecycleChangeRequest request,
                                                       final LifecycleCommand command,
                                                       final LifecycleAction action) {
        Objects.requireNonNull(request, "request");
        final String versionTag = Optional.ofNullable(request.correlationId())
            .filter(s -> !s.isBlank())
            .orElse(DEFAULT_VERSION_TAG);
        final ManageMintLifecycleRequest useCaseRequest = new ManageMintLifecycleRequest(
            mintId,
            operatorIdentity.currentOperatorId(),
            command,
            versionTag,
            null,
            request.correlationId()
        );
        try {
            final ManageMintLifecycleResponse response = lifecycleUseCase.handle(useCaseRequest);
            final LifecycleSummary summary = summaryPresenter.present(action,
                response.mintId(), null, response.lifecycleState().name(), response.versionTag(), true);
            return apiPresenter.present(summary);
        } catch (IllegalStateException e) {
            throw mapDomainException(e);
        } catch (IllegalArgumentException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
        }
    }

    private static AdminServiceException mapDomainException(final IllegalStateException e) {
        final String message = e.getMessage();
        if (message != null && message.contains("not found")) {
            return new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found", message);
        }
        if (message != null && message.contains("Cannot transition")) {
            return new AdminServiceException(HttpStatus.CONFLICT, "invalid_transition", message);
        }
        if (message != null && message.contains("already active for unit")) {
            return new AdminServiceException(HttpStatus.CONFLICT, "unit_conflict", message);
        }
        return new AdminServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "lifecycle_error", message);
    }

    private static java.util.Map<String, String> extractConfigurationParameters(
            final java.util.Map<String, Object> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return java.util.Map.of();
        }
        final java.util.Map<String, String> params = new java.util.HashMap<>();
        configuration.forEach((key, value) -> {
            if (value instanceof String str && !str.isBlank()) {
                params.put(key, str);
            } else if (value instanceof Number number) {
                // Configuration is stored as strings, but an operator writing a numeric
                // setting such as "cashu.input_fee_ppk": 100 sends a JSON number. Keeping
                // only strings dropped it silently, so the mint was provisioned without
                // the setting and nothing said so.
                params.put(key, number.toString());
            }
        });
        return java.util.Map.copyOf(params);
    }

    private static String extractVersionTag(final java.util.Map<String, Object> configuration, final String fallback) {
        return Optional.ofNullable(configuration)
            .map(config -> {
                final Object primary = config.get("versionTag");
                if (primary instanceof String str && !str.isBlank()) {
                    return str;
                }
                final Object alternative = config.get("version_tag");
                if (alternative instanceof String alt && !alt.isBlank()) {
                    return alt;
                }
                return null;
            })
            .filter(tag -> !tag.isBlank())
            .orElse(fallback);
    }
}
