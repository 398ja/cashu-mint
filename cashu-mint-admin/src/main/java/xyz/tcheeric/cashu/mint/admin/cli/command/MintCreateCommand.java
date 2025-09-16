package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryCliPresenter;

@Command(name = "create",
         description = "Provision a new mint with the supplied configuration.",
         mixinStandardHelpOptions = true)
public final class MintCreateCommand extends MintLifecycleCommandSupport {

    public MintCreateCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final LifecycleSummaryCliPresenter summaryPresenter) {
        super(LifecycleAction.CREATE, lifecyclePort, payloadMapper, summaryPresenter);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Create mint '" + request.mintId() + "' with version tag '" + request.versionTag() + "'?";
    }
}
