package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryCliPresenter;

@Command(name = "retire",
         description = "Decommission a mint and mark it as retired.",
         mixinStandardHelpOptions = true)
public final class MintRetireCommand extends MintLifecycleCommandSupport {

    public MintRetireCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final LifecycleSummaryCliPresenter summaryPresenter) {
        super(LifecycleAction.RETIRE, lifecyclePort, payloadMapper, summaryPresenter);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Retire mint '" + request.mintId() + "' with version tag '" + request.versionTag() + "'?";
    }
}
