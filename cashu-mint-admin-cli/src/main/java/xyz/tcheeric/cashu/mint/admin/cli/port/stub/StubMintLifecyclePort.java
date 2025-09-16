package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;

/**
 * In-memory lifecycle port used for wiring the CLI without backend integration.
 */
public class StubMintLifecyclePort implements MintLifecyclePort {

    private final Map<String, MintState> states = new ConcurrentHashMap<>();
    private final LifecycleSummaryPresenter presenter;

    public StubMintLifecyclePort() {
        this(new LifecycleSummaryPresenter());
    }

    public StubMintLifecyclePort(final LifecycleSummaryPresenter presenter) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
    }

    public StubMintLifecyclePort seed(final String mintId,
                                      final LifecycleState.State state,
                                      final String versionTag) {
        states.put(mintId, new MintState(state, versionTag));
        return this;
    }

    @Override
    public LifecycleSummary execute(final MintLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        final MintLifecycleRequest request = command.request();
        return switch (command.operation()) {
            case CREATE -> handleCreate(request);
            case UPDATE -> handleUpdate(request);
            case PAUSE -> handleTransition(request, LifecycleState.State.SUSPENDED);
            case RESUME -> handleTransition(request, LifecycleState.State.ACTIVE);
            case RETIRE -> handleTransition(request, LifecycleState.State.DECOMMISSIONED);
        };
    }

    private LifecycleSummary handleCreate(final MintLifecycleRequest request) {
        final MintState existing = states.get(request.mintId());
        if (existing != null) {
            return presenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(
                LifecycleAction.CREATE,
                request.mintId(),
                existing.state.name(),
                existing.state.name(),
                existing.versionTag,
                false
            ));
        }
        final MintState created = new MintState(LifecycleState.State.PROVISIONED, request.versionTag());
        states.put(request.mintId(), created);
        return presenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(
            LifecycleAction.CREATE,
            request.mintId(),
            null,
            created.state.name(),
            created.versionTag,
            true
        ));
    }

    private LifecycleSummary handleUpdate(final MintLifecycleRequest request) {
        final MintState state = requireExistingState(request.mintId());
        final boolean changed = !Objects.equals(state.versionTag, request.versionTag());
        state.versionTag = request.versionTag();
        return presenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(
            LifecycleAction.UPDATE,
            request.mintId(),
            state.state.name(),
            state.state.name(),
            state.versionTag,
            changed
        ));
    }

    private LifecycleSummary handleTransition(final MintLifecycleRequest request,
                                              final LifecycleState.State target) {
        final MintState state = requireExistingState(request.mintId());
        final LifecycleState.State previous = state.state;
        final boolean changed = previous != target;
        state.state = target;
        state.versionTag = request.versionTag();
        return presenter.present(new LifecycleSummaryPresenter.LifecycleSummaryRequest(
            actionFor(target),
            request.mintId(),
            previous.name(),
            state.state.name(),
            state.versionTag,
            changed
        ));
    }

    private LifecycleAction actionFor(final LifecycleState.State target) {
        return switch (target) {
            case PROVISIONED -> LifecycleAction.CREATE;
            case ACTIVE -> LifecycleAction.RESUME;
            case SUSPENDED -> LifecycleAction.PAUSE;
            case DECOMMISSIONED -> LifecycleAction.RETIRE;
        };
    }

    private MintState requireExistingState(final String mintId) {
        final MintState state = states.get(mintId);
        if (state == null) {
            throw new IllegalStateException("mint not found: " + mintId);
        }
        return state;
    }

    private static final class MintState {
        private LifecycleState.State state;
        private String versionTag;

        private MintState(final LifecycleState.State state, final String versionTag) {
            this.state = Objects.requireNonNull(state, "state");
            this.versionTag = Objects.requireNonNull(versionTag, "versionTag");
        }
    }
}
