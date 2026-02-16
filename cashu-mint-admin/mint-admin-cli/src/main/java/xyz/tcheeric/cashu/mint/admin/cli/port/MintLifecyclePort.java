package xyz.tcheeric.cashu.mint.admin.cli.port;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;

public interface MintLifecyclePort {

    LifecycleSummary execute(MintLifecycleCommand command);

    record MintLifecycleCommand(LifecycleAction operation, MintLifecycleRequest request) {

        public MintLifecycleCommand {
            operation = Objects.requireNonNull(operation, "operation");
            request = Objects.requireNonNull(request, "request");
        }
    }
}
