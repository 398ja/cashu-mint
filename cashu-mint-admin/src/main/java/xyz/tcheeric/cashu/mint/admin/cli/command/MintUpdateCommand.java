package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleOperation;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;

@Command(name = "update",
         description = "Apply a configuration update to an existing mint.",
         mixinStandardHelpOptions = true)
public final class MintUpdateCommand extends MintLifecycleCommandSupport {

    public MintUpdateCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final ResponseRenderingService renderingService) {
        super(MintLifecycleOperation.UPDATE, lifecyclePort, payloadMapper, renderingService);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Update configuration for mint '" + request.mintId() + "' to version '" + request.versionTag() + "'?";
    }
}
