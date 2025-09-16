package xyz.tcheeric.cashu.mint.admin.cli.port;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleOperation;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleResponse;

public interface MintLifecyclePort {

    MintLifecycleResponse execute(MintLifecycleCommand command);

    record MintLifecycleCommand(MintLifecycleOperation operation, MintLifecycleRequest request) {

        public MintLifecycleCommand {
            operation = Objects.requireNonNull(operation, "operation");
            request = Objects.requireNonNull(request, "request");
        }
    }
}
