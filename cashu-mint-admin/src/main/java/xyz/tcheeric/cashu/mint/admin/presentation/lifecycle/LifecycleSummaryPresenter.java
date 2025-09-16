package xyz.tcheeric.cashu.mint.admin.presentation.lifecycle;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Produces lifecycle summaries with consistent messaging across adapters.
 */
public final class LifecycleSummaryPresenter {

    private final Map<LifecycleAction, MessageTemplate> templates;

    public LifecycleSummaryPresenter() {
        this.templates = defaultTemplates();
    }

    public LifecycleSummaryPresenter(final Map<LifecycleAction, MessageTemplate> templates) {
        this.templates = Map.copyOf(Objects.requireNonNull(templates, "templates"));
    }

    public LifecycleSummary present(final LifecycleSummaryRequest request) {
        Objects.requireNonNull(request, "request");
        final MessageTemplate template = templates.get(request.operation());
        if (template == null) {
            throw new IllegalArgumentException("No message template configured for " + request.operation());
        }
        final String message = template.messageFor(request.changed());
        return new LifecycleSummary(request.operation(),
            request.mintId(),
            request.previousState(),
            request.currentState(),
            request.versionTag(),
            request.changed(),
            message);
    }

    public LifecycleSummary present(final LifecycleAction operation,
                                    final String mintId,
                                    final String previousState,
                                    final String currentState,
                                    final String versionTag,
                                    final boolean changed) {
        return present(new LifecycleSummaryRequest(operation, mintId, previousState, currentState, versionTag, changed));
    }

    private Map<LifecycleAction, MessageTemplate> defaultTemplates() {
        final Map<LifecycleAction, MessageTemplate> defaults = new EnumMap<>(LifecycleAction.class);
        defaults.put(LifecycleAction.CREATE, new MessageTemplate("Mint created", "Mint already exists"));
        defaults.put(LifecycleAction.UPDATE, new MessageTemplate("Mint updated", "No changes applied"));
        defaults.put(LifecycleAction.PAUSE, new MessageTemplate("Mint paused", "Mint already suspended"));
        defaults.put(LifecycleAction.RESUME, new MessageTemplate("Mint resumed", "Mint already active"));
        defaults.put(LifecycleAction.RETIRE, new MessageTemplate("Mint retired", "Mint already retired"));
        return defaults;
    }

    public record LifecycleSummaryRequest(LifecycleAction operation,
                                          String mintId,
                                          String previousState,
                                          String currentState,
                                          String versionTag,
                                          boolean changed) {

        public LifecycleSummaryRequest {
            operation = Objects.requireNonNull(operation, "operation");
            mintId = requireText(mintId, "mintId");
            currentState = requireText(currentState, "currentState");
            versionTag = requireText(versionTag, "versionTag");
        }

        private static String requireText(final String value, final String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value;
        }
    }

    public record MessageTemplate(String changedMessage, String idempotentMessage) {

        public MessageTemplate {
            changedMessage = Objects.requireNonNull(changedMessage, "changedMessage");
            idempotentMessage = Objects.requireNonNull(idempotentMessage, "idempotentMessage");
        }

        String messageFor(final boolean changed) {
            return changed ? changedMessage : idempotentMessage;
        }
    }
}
