package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusResponse;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;

import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "mint",
         description = "Inspect the state of a mint",
         mixinStandardHelpOptions = true)
public final class MintCommand implements Callable<Integer> {

    private final MintStatusPort statusPort;
    private final CommandPayloadMapper payloadMapper;
    private final ResponseRenderingService renderingService;

    @Spec
    private CommandSpec spec;

    @CommandLine.Mixin
    private final CommandIOOptions ioOptions = new CommandIOOptions();

    public MintCommand(final MintStatusPort statusPort,
                       final CommandPayloadMapper payloadMapper,
                       final ResponseRenderingService renderingService) {
        this.statusPort = Objects.requireNonNull(statusPort, "statusPort");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.renderingService = Objects.requireNonNull(renderingService, "renderingService");
    }

    @Override
    public Integer call() {
        final MintStatusRequest request = ioOptions
            .readPayload(payloadMapper, MintStatusRequest.class)
            .orElseGet(MintStatusRequest::defaultRequest);
        final MintStatusResponse response = statusPort.fetchStatus(request);
        spec.commandLine().getOut().println(renderingService.render(response, ioOptions.outputFormat()));
        return CommandLine.ExitCode.OK;
    }
}
