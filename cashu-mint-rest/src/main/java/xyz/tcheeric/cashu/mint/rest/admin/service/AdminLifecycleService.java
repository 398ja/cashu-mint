package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.MintMetadataDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.presenter.LifecycleSummaryApiPresenter;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory lifecycle workflow implementation mirroring the CLI behaviour.
 */
@Service
public class AdminLifecycleService {

    private static final String DEFAULT_VERSION_TAG = "v1";

    private final ConcurrentMap<String, MintRecord> lifecycleState;
    private final LifecycleSummaryPresenter summaryPresenter;
    private final LifecycleSummaryApiPresenter apiPresenter;

    public AdminLifecycleService() {
        this(new ConcurrentHashMap<>(), new LifecycleSummaryPresenter(), new LifecycleSummaryApiPresenter());
    }

    AdminLifecycleService(final ConcurrentMap<String, MintRecord> lifecycleState,
                          final LifecycleSummaryPresenter summaryPresenter,
                          final LifecycleSummaryApiPresenter apiPresenter) {
        this.lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        this.summaryPresenter = Objects.requireNonNull(summaryPresenter, "summaryPresenter");
        this.apiPresenter = Objects.requireNonNull(apiPresenter, "apiPresenter");
    }

    public LifecycleActionResponse createMint(final CreateMintRequest request) {
        Objects.requireNonNull(request, "request");
        final AtomicReference<LifecycleSummary> summaryRef = new AtomicReference<>();
        lifecycleState.compute(request.mintId(), (id, current) -> {
            if (current != null) {
                summaryRef.set(present(LifecycleAction.CREATE, id, current.status.name(), current.status.name(),
                    current.versionTag, false));
                return current;
            }
            final String versionTag = extractVersionTag(request.configuration(), DEFAULT_VERSION_TAG);
            final MintRecord created = new MintRecord(LifecycleStatus.PROVISIONED, versionTag,
                request.metadata(), normalizeConfiguration(request.configuration()));
            summaryRef.set(present(LifecycleAction.CREATE, id, null, created.status.name(), created.versionTag, true));
            return created;
        });
        return apiPresenter.present(summaryRef.get());
    }

    public LifecycleActionResponse updateMint(final String mintId, final UpdateMintRequest request) {
        Objects.requireNonNull(request, "request");
        final AtomicReference<LifecycleSummary> summaryRef = new AtomicReference<>();
        lifecycleState.compute(mintId, (id, current) -> {
            if (current == null) {
                summaryRef.set(null);
                return null;
            }
            final Map<String, Object> normalizedConfig = normalizeConfiguration(request.configuration());
            final String newVersionTag = extractVersionTag(request.configuration(), request.revisionId());
            final boolean metadataChanged = !Objects.equals(current.metadata, request.metadata());
            final boolean configChanged = !Objects.equals(current.configuration, normalizedConfig);
            final boolean versionChanged = !Objects.equals(current.versionTag, newVersionTag);
            current.metadata = request.metadata();
            current.configuration = normalizedConfig;
            current.versionTag = newVersionTag;
            final boolean changed = metadataChanged || configChanged || versionChanged;
            summaryRef.set(present(LifecycleAction.UPDATE, id, current.status.name(), current.status.name(),
                current.versionTag, changed));
            return current;
        });
        final LifecycleSummary summary = summaryRef.get();
        if (summary == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found", "Mint not found: " + mintId);
        }
        return apiPresenter.present(summary);
    }

    public LifecycleActionResponse pauseMint(final String mintId, final LifecycleChangeRequest request) {
        return apiPresenter.present(transition(mintId, request, LifecycleStatus.SUSPENDED, LifecycleAction.PAUSE));
    }

    public LifecycleActionResponse resumeMint(final String mintId, final LifecycleChangeRequest request) {
        return apiPresenter.present(transition(mintId, request, LifecycleStatus.ACTIVE, LifecycleAction.RESUME));
    }

    public LifecycleActionResponse retireMint(final String mintId, final LifecycleChangeRequest request) {
        return apiPresenter.present(transition(mintId, request, LifecycleStatus.DECOMMISSIONED, LifecycleAction.RETIRE));
    }

    private LifecycleSummary transition(final String mintId,
                                        final LifecycleChangeRequest request,
                                        final LifecycleStatus target,
                                        final LifecycleAction operation) {
        Objects.requireNonNull(request, "request");
        final AtomicReference<LifecycleSummary> summaryRef = new AtomicReference<>();
        lifecycleState.compute(mintId, (id, current) -> {
            if (current == null) {
                summaryRef.set(null);
                return null;
            }
            final LifecycleStatus previous = current.status;
            final boolean changed = previous != target;
            current.status = target;
            current.versionTag = transitionVersionTag(request, current.versionTag);
            summaryRef.set(present(operation, id, previous.name(), current.status.name(), current.versionTag, changed));
            return current;
        });
        final LifecycleSummary summary = summaryRef.get();
        if (summary == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found", "Mint not found: " + mintId);
        }
        return summary;
    }

    private LifecycleSummary present(final LifecycleAction operation,
                                     final String mintId,
                                     final String previousState,
                                     final String currentState,
                                     final String versionTag,
                                     final boolean changed) {
        return summaryPresenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(operation,
            mintId, previousState, currentState, versionTag, changed));
    }

    private static Map<String, Object> normalizeConfiguration(final Map<String, Object> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(configuration);
    }

    private static String extractVersionTag(final Map<String, Object> configuration, final String fallback) {
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

    private static String transitionVersionTag(final LifecycleChangeRequest request, final String currentVersionTag) {
        if (request.correlationId() != null && !request.correlationId().isBlank()) {
            return request.correlationId();
        }
        final String contextId = CorrelationIdContext.currentId();
        if (contextId != null && !contextId.isBlank()) {
            return contextId;
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            return request.reason().trim().toLowerCase(Locale.ROOT).replace(' ', '-');
        }
        return currentVersionTag != null && !currentVersionTag.isBlank() ? currentVersionTag : DEFAULT_VERSION_TAG;
    }

    private enum LifecycleStatus {
        PROVISIONED,
        ACTIVE,
        SUSPENDED,
        DECOMMISSIONED
    }

    private static final class MintRecord {
        private LifecycleStatus status;
        private String versionTag;
        private MintMetadataDto metadata;
        private Map<String, Object> configuration;

        private MintRecord(final LifecycleStatus status,
                           final String versionTag,
                           final MintMetadataDto metadata,
                           final Map<String, Object> configuration) {
            this.status = Objects.requireNonNull(status, "status");
            this.versionTag = versionTag;
            this.metadata = metadata;
            this.configuration = configuration;
        }
    }
}
