package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ManageConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ManageConfigurationResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.ApplyConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.ConfigurationActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.ConfigurationRevisionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.PreviewConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.RollbackConfigurationRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Delegates configuration workflows to {@link ManageConfigurationUseCase}.
 */
@Service
public class AdminConfigurationService {

    private static final String DEFAULT_VERSION_TAG = "v1";

    private final ManageConfigurationUseCase configurationUseCase;
    private final ConfigurationSetRepository configurationSetRepository;

    public AdminConfigurationService(final ManageConfigurationUseCase configurationUseCase,
                                     final ConfigurationSetRepository configurationSetRepository) {
        this.configurationUseCase = Objects.requireNonNull(configurationUseCase,
            "configuration use case must not be null");
        this.configurationSetRepository = Objects.requireNonNull(configurationSetRepository,
            "configuration set repository must not be null");
    }

    public PagedResponse<ConfigurationRevisionResponse> listRevisions(final String mintId,
                                                                       final int page, final int size) {
        final List<ConfigurationSet> revisions = configurationSetRepository.findByMintId(MintId.fromString(mintId));
        final List<ConfigurationRevisionResponse> items = revisions.stream()
                .map(r -> new ConfigurationRevisionResponse(
                        r.revisionId().value(),
                        r.auditMetadata().action(),
                        r.parameters()))
                .toList();
        return PagedResponse.of(items, page, size);
    }

    public ConfigurationActionResponse previewConfiguration(final String mintId,
                                                             final PreviewConfigurationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageConfigurationResponse response = configurationUseCase.handle(
                new ManageConfigurationRequest(mintId, request.requestedBy().id(),
                    request.baseRevisionId(), ConfigurationCommand.VALIDATE,
                    DEFAULT_VERSION_TAG, normalizeParameters(request.proposedConfiguration())));
            return new ConfigurationActionResponse(response.mintId(), response.appliedRevision() + "-preview",
                response.parameters(), "Preview based on revision " + request.baseRevisionId());
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public ConfigurationActionResponse applyConfiguration(final String mintId,
                                                           final ApplyConfigurationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageConfigurationResponse response = configurationUseCase.handle(
                new ManageConfigurationRequest(mintId, request.requestedBy().id(),
                    null, ConfigurationCommand.APPLY,
                    DEFAULT_VERSION_TAG, normalizeParameters(request.proposedConfiguration())));
            return new ConfigurationActionResponse(response.mintId(), response.appliedRevision(),
                response.parameters(), "Configuration applied: " + request.changeSummary());
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public ConfigurationActionResponse rollbackConfiguration(final String mintId,
                                                              final RollbackConfigurationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final ManageConfigurationResponse response = configurationUseCase.handle(
                new ManageConfigurationRequest(mintId, request.requestedBy().id(),
                    request.targetRevisionId(), ConfigurationCommand.ROLLBACK,
                    DEFAULT_VERSION_TAG));
            return new ConfigurationActionResponse(response.mintId(), response.appliedRevision(),
                response.parameters(), "Rolled back to revision " + request.targetRevisionId()
                    + ": " + request.reason());
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    private static Map<String, String> normalizeParameters(final Map<String, Object> parameters) {
        if (parameters == null) {
            return Map.of();
        }
        final Map<String, String> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : parameters.entrySet()) {
            if (entry.getValue() != null) {
                normalized.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return Map.copyOf(normalized);
    }

    private static AdminServiceException mapDomainException(final IllegalStateException e) {
        final String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (message.contains("not found")) {
            return new AdminServiceException(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
        }
        if (message.contains("revision conflict") || message.contains("already exists")) {
            return new AdminServiceException(HttpStatus.CONFLICT, "revision_conflict", e.getMessage());
        }
        return new AdminServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "configuration_error", e.getMessage());
    }
}
