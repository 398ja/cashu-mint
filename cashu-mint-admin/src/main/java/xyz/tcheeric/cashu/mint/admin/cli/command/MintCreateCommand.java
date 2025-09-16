package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleOperation;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;

@Command(name = "create",
         description = "Provision a new mint with the supplied configuration.",
         mixinStandardHelpOptions = true)
public final class MintCreateCommand extends MintLifecycleCommandSupport {

    public MintCreateCommand(final MintLifecyclePort lifecyclePort,
                             final CommandPayloadMapper payloadMapper,
                             final ResponseRenderingService renderingService) {
        super(MintLifecycleOperation.CREATE, lifecyclePort, payloadMapper, renderingService);
    }

    @Override
    protected String confirmationPrompt(final MintLifecycleRequest request) {
        return "Create mint '" + request.mintId() + "' with version tag '" + request.versionTag() + "'?";
    }
}
