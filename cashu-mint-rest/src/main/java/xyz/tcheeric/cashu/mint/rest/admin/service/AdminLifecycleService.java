package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.MintMetadataDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.UpdateMintRequest;

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

    private final ConcurrentMap<String, MintRecord> lifecycleState = new ConcurrentHashMap<>();

    public LifecycleActionResponse createMint(final CreateMintRequest request) {
        Objects.requireNonNull(request, "request");
        final AtomicReference<LifecycleActionResponse> responseRef = new AtomicReference<>();
        lifecycleState.compute(request.mintId(), (id, current) -> {
            if (current != null) {
                responseRef.set(new LifecycleActionResponse(
                        LifecycleOperation.CREATE.name(),
                        id,
                        current.status.name(),
                        current.status.name(),
                        current.versionTag,
                        false,
                        "Mint already exists"));
                return current;
            }
            final String versionTag = extractVersionTag(request.configuration(), DEFAULT_VERSION_TAG);
            final MintRecord created = new MintRecord(LifecycleStatus.PROVISIONED, versionTag,
                    request.metadata(), normalizeConfiguration(request.configuration()));
            responseRef.set(new LifecycleActionResponse(
                    LifecycleOperation.CREATE.name(),
                    id,
                    null,
                    created.status.name(),
                    created.versionTag,
                    true,
                    "Mint created"));
            return created;
        });
        return responseRef.get();
    }

    public LifecycleActionResponse updateMint(final String mintId, final UpdateMintRequest request) {
        Objects.requireNonNull(request, "request");
        final AtomicReference<LifecycleActionResponse> responseRef = new AtomicReference<>();
        lifecycleState.compute(mintId, (id, current) -> {
            if (current == null) {
                responseRef.set(null);
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
            responseRef.set(new LifecycleActionResponse(
                    LifecycleOperation.UPDATE.name(),
                    id,
                    current.status.name(),
                    current.status.name(),
                    current.versionTag,
                    changed,
                    changed ? "Mint updated" : "No changes applied"));
            return current;
        });
        final LifecycleActionResponse response = responseRef.get();
        if (response == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found", "Mint not found: " + mintId);
        }
        return response;
    }

    public LifecycleActionResponse pauseMint(final String mintId, final LifecycleChangeRequest request) {
        return transition(mintId, request, LifecycleStatus.SUSPENDED,
                LifecycleOperation.PAUSE, "Mint paused", "Mint already suspended");
    }

    public LifecycleActionResponse resumeMint(final String mintId, final LifecycleChangeRequest request) {
        return transition(mintId, request, LifecycleStatus.ACTIVE,
                LifecycleOperation.RESUME, "Mint resumed", "Mint already active");
    }

    public LifecycleActionResponse retireMint(final String mintId, final LifecycleChangeRequest request) {
        return transition(mintId, request, LifecycleStatus.DECOMMISSIONED,
                LifecycleOperation.RETIRE, "Mint retired", "Mint already retired");
    }

    private LifecycleActionResponse transition(final String mintId,
                                                final LifecycleChangeRequest request,
                                                final LifecycleStatus target,
                                                final LifecycleOperation operation,
                                                final String successMessage,
                                                final String idempotentMessage) {
        Objects.requireNonNull(request, "request");
        final AtomicReference<LifecycleActionResponse> responseRef = new AtomicReference<>();
        lifecycleState.compute(mintId, (id, current) -> {
            if (current == null) {
                responseRef.set(null);
                return null;
            }
            final LifecycleStatus previous = current.status;
            final boolean changed = previous != target;
            current.status = target;
            current.versionTag = transitionVersionTag(request, current.versionTag);
            final String message = changed ? successMessage : idempotentMessage;
            responseRef.set(new LifecycleActionResponse(
                    operation.name(),
                    id,
                    previous.name(),
                    current.status.name(),
                    current.versionTag,
                    changed,
                    message));
            return current;
        });
        final LifecycleActionResponse response = responseRef.get();
        if (response == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "mint_not_found", "Mint not found: " + mintId);
        }
        return response;
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

    private enum LifecycleOperation {
        CREATE,
        UPDATE,
        PAUSE,
        RESUME,
        RETIRE
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
