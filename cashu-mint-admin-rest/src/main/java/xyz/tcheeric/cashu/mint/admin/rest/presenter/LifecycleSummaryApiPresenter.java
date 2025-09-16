package xyz.tcheeric.cashu.mint.admin.rest.presenter;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleActionResponse;

/**
 * Converts lifecycle summaries into API response payloads.
 */
public final class LifecycleSummaryApiPresenter {

    public LifecycleActionResponse present(final LifecycleSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new LifecycleActionResponse(
            summary.operation().name(),
            summary.mintId(),
            summary.previousState(),
            summary.currentState(),
            summary.versionTag(),
            summary.changed(),
            summary.message()
        );
    }
}
