package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.ApplyConfigurationRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.ConfigurationActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.PreviewConfigurationRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.RollbackConfigurationRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple in-memory configuration history manager for administrative workflows.
 */
@Service
public class AdminConfigurationService {

    private static final Map<String, String> BASELINE_CONFIGURATION = Map.of(
            "currency", "sat",
            "maxTokens", "1000"
    );

    private final ConcurrentMap<String, ConfigurationHistory> histories = new ConcurrentHashMap<>();

    public ConfigurationActionResponse previewConfiguration(final String mintId,
                                                             final PreviewConfigurationRequest request) {
        Objects.requireNonNull(request, "request");
        final ConfigurationHistory history = histories.computeIfAbsent(mintId, k -> new ConfigurationHistory());
        return history.preview(mintId, request);
    }

    public ConfigurationActionResponse applyConfiguration(final String mintId,
                                                           final ApplyConfigurationRequest request) {
        Objects.requireNonNull(request, "request");
        final ConfigurationHistory history = histories.computeIfAbsent(mintId, k -> new ConfigurationHistory());
        return history.apply(mintId, request);
    }

    public ConfigurationActionResponse rollbackConfiguration(final String mintId,
                                                              final RollbackConfigurationRequest request) {
        Objects.requireNonNull(request, "request");
        final ConfigurationHistory history = histories.computeIfAbsent(mintId, k -> new ConfigurationHistory());
        return history.rollback(mintId, request);
    }

    private static Map<String, String> normalizeParameters(final Map<String, Object> parameters,
                                                           final Map<String, String> startingPoint) {
        final Map<String, String> merged = new LinkedHashMap<>(startingPoint);
        if (parameters == null) {
            return merged;
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, String.valueOf(value));
            }
        }
        return Map.copyOf(merged);
    }

    private static final class ConfigurationHistory {
        private final List<ConfigurationRevision> revisions = new ArrayList<>();
        private int nextRevision = 1;

        private ConfigurationHistory() {
            revisions.add(new ConfigurationRevision("rev-0", BASELINE_CONFIGURATION));
        }

        private synchronized ConfigurationActionResponse preview(final String mintId,
                                                                  final PreviewConfigurationRequest request) {
            final ConfigurationRevision current = currentRevision();
            if (!Objects.equals(current.revisionId, request.baseRevisionId())) {
                throw new AdminServiceException(HttpStatus.CONFLICT, "revision_conflict",
                        "Current revision is %s".formatted(current.revisionId));
            }
            final Map<String, String> previewParameters = normalizeParameters(request.proposedConfiguration(),
                    current.parameters);
            final String revisionId = request.baseRevisionId() + "-preview";
            return new ConfigurationActionResponse(mintId, revisionId, previewParameters,
                    "Preview based on " + request.baseRevisionId());
        }

        private synchronized ConfigurationActionResponse apply(final String mintId,
                                                                final ApplyConfigurationRequest request) {
            final ConfigurationRevision current = currentRevision();
            final Map<String, String> merged = normalizeParameters(request.proposedConfiguration(), current.parameters);
            final String revisionId = "rev-" + nextRevision++;
            final ConfigurationRevision applied = new ConfigurationRevision(revisionId, merged);
            revisions.add(applied);
            return new ConfigurationActionResponse(mintId, revisionId, applied.parameters,
                    "Configuration applied: " + request.changeSummary());
        }

        private synchronized ConfigurationActionResponse rollback(final String mintId,
                                                                   final RollbackConfigurationRequest request) {
            final int targetIndex = findRevisionIndex(request.targetRevisionId())
                    .orElseThrow(() -> new AdminServiceException(HttpStatus.NOT_FOUND, "revision_not_found",
                            "Revision not found: " + request.targetRevisionId()));
            while (revisions.size() - 1 > targetIndex) {
                revisions.remove(revisions.size() - 1);
            }
            final ConfigurationRevision current = currentRevision();
            nextRevision = parseRevisionNumber(current.revisionId) + 1;
            return new ConfigurationActionResponse(mintId, current.revisionId, current.parameters,
                    "Rolled back to " + current.revisionId + ": " + request.reason());
        }

        private ConfigurationRevision currentRevision() {
            return revisions.get(revisions.size() - 1);
        }

        private Optional<Integer> findRevisionIndex(final String revisionId) {
            for (int i = 0; i < revisions.size(); i++) {
                if (Objects.equals(revisions.get(i).revisionId, revisionId)) {
                    return Optional.of(i);
                }
            }
            return Optional.empty();
        }

        private static int parseRevisionNumber(final String revisionId) {
            if (revisionId == null || !revisionId.startsWith("rev-")) {
                return 0;
            }
            try {
                return Integer.parseInt(revisionId.substring(4));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
    }

    private record ConfigurationRevision(String revisionId, Map<String, String> parameters) {
        private ConfigurationRevision {
            revisionId = Objects.requireNonNull(revisionId, "revisionId");
            parameters = Map.copyOf(parameters);
        }
    }
}
