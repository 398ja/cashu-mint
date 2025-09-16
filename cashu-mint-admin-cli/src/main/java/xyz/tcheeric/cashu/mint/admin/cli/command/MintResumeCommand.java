package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle.LifecycleSummaryCliPresenter;

@Command(name = "resume",
         description = "Reactivate a paused mint.",
         mixinStandardHelpOptions = true)
public final class MintResumeCommand extends MintLifecycleCommandSupport {

    public MintResumeCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final LifecycleSummaryCliPresenter summaryPresenter) {
        super(LifecycleAction.RESUME, lifecyclePort, payloadMapper, summaryPresenter);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Resume mint '" + request.mintId() + "' with version tag '" + request.versionTag() + "'?";
    }
}
