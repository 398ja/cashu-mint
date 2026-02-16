package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle.LifecycleSummaryCliPresenter;

@Command(name = "pause",
         description = "Suspend mint operations until they are explicitly resumed.",
         mixinStandardHelpOptions = true)
public final class MintPauseCommand extends MintLifecycleCommandSupport {

    public MintPauseCommand(final MintLifecyclePort lifecyclePort,
                            final CommandPayloadMapper payloadMapper,
                            final LifecycleSummaryCliPresenter summaryPresenter) {
        super(LifecycleAction.PAUSE, lifecyclePort, payloadMapper, summaryPresenter);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Pause mint '" + request.mintId() + "' with version tag '" + request.versionTag() + "'?";
    }
}
