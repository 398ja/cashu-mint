package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryCliPresenter;

@Command(name = "update",
         description = "Apply a configuration update to an existing mint.",
         mixinStandardHelpOptions = true)
public final class MintUpdateCommand extends MintLifecycleCommandSupport {

    public MintUpdateCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final LifecycleSummaryCliPresenter summaryPresenter) {
        super(LifecycleAction.UPDATE, lifecyclePort, payloadMapper, summaryPresenter);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Update configuration for mint '" + request.mintId() + "' to version '" + request.versionTag() + "'?";
    }
}
