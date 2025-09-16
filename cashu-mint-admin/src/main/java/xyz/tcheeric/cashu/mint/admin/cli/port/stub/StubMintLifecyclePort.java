package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleOperation;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleResponse;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;

/**
 * In-memory lifecycle port used for wiring the CLI without backend integration.
 */
public class StubMintLifecyclePort implements MintLifecyclePort {

    private final Map<String, MintState> states = new ConcurrentHashMap<>();

    public StubMintLifecyclePort seed(final String mintId,
                                      final LifecycleState.State state,
                                      final String versionTag) {
        states.put(mintId, new MintState(state, versionTag));
        return this;
    }

    @Override
    public MintLifecycleResponse execute(final MintLifecycleCommand command) {
        Objects.requireNonNull(command, "command");
        final MintLifecycleRequest request = command.request();
        return switch (command.operation()) {
            case CREATE -> handleCreate(request);
            case UPDATE -> handleUpdate(request);
            case PAUSE -> handleTransition(request, LifecycleState.State.SUSPENDED,
                "Mint paused", "Mint already suspended");
            case RESUME -> handleTransition(request, LifecycleState.State.ACTIVE,
                "Mint resumed", "Mint already active");
            case RETIRE -> handleTransition(request, LifecycleState.State.DECOMMISSIONED,
                "Mint retired", "Mint already retired");
        };
    }

    private MintLifecycleResponse handleCreate(final MintLifecycleRequest request) {
        final MintState existing = states.get(request.mintId());
        if (existing != null) {
            return new MintLifecycleResponse(MintLifecycleOperation.CREATE, request.mintId(), existing.state,
                existing.state, existing.versionTag, false, "Mint already exists");
        }
        final MintState created = new MintState(LifecycleState.State.PROVISIONED, request.versionTag());
        states.put(request.mintId(), created);
        return new MintLifecycleResponse(MintLifecycleOperation.CREATE, request.mintId(), null,
            created.state, created.versionTag, true, "Mint created");
    }

    private MintLifecycleResponse handleUpdate(final MintLifecycleRequest request) {
        final MintState state = requireExistingState(request.mintId());
        final boolean changed = !Objects.equals(state.versionTag, request.versionTag());
        state.versionTag = request.versionTag();
        return new MintLifecycleResponse(MintLifecycleOperation.UPDATE, request.mintId(), state.state,
            state.state, state.versionTag, changed,
            changed ? "Configuration updated" : "Configuration already up to date");
    }

    private MintLifecycleResponse handleTransition(final MintLifecycleRequest request,
                                                   final LifecycleState.State target,
                                                   final String successMessage,
                                                   final String idempotentMessage) {
        final MintState state = requireExistingState(request.mintId());
        final LifecycleState.State previous = state.state;
        final boolean changed = previous != target;
        state.state = target;
        state.versionTag = request.versionTag();
        return new MintLifecycleResponse(operationFor(target), request.mintId(), previous,
            state.state, state.versionTag, changed, changed ? successMessage : idempotentMessage);
    }

    private MintLifecycleOperation operationFor(final LifecycleState.State target) {
        return switch (target) {
            case PROVISIONED -> MintLifecycleOperation.CREATE;
            case ACTIVE -> MintLifecycleOperation.RESUME;
            case SUSPENDED -> MintLifecycleOperation.PAUSE;
            case DECOMMISSIONED -> MintLifecycleOperation.RETIRE;
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
