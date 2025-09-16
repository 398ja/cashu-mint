package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleOperation;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;

@Command(name = "resume",
         description = "Reactivate a paused mint.",
         mixinStandardHelpOptions = true)
public final class MintResumeCommand extends MintLifecycleCommandSupport {

    public MintResumeCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final ResponseRenderingService renderingService) {
        super(MintLifecycleOperation.RESUME, lifecyclePort, payloadMapper, renderingService);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Resume mint '" + request.mintId() + "' with version tag '" + request.versionTag() + "'?";
    }
}
