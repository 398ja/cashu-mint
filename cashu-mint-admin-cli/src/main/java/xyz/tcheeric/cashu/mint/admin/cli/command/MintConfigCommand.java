package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigResponse;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintConfigPort;

import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "config",
         description = "Inspect or apply mint configuration",
         mixinStandardHelpOptions = true)
public final class MintConfigCommand implements Callable<Integer> {

    private final MintConfigPort configPort;
    private final CommandPayloadMapper payloadMapper;
    private final ResponseRenderingService renderingService;

    @Spec
    private CommandSpec spec;

    @Option(names = {"-m", "--mint-id"},
            description = "Mint identifier to target when no payload is provided.",
            defaultValue = MintConfigRequest.DEFAULT_MINT_ID)
    private String mintId = MintConfigRequest.DEFAULT_MINT_ID;

    @CommandLine.Mixin
    private final CommandIOOptions ioOptions = new CommandIOOptions();

    public MintConfigCommand(final MintConfigPort configPort,
                             final CommandPayloadMapper payloadMapper,
                             final ResponseRenderingService renderingService) {
        this.configPort = Objects.requireNonNull(configPort, "configPort");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.renderingService = Objects.requireNonNull(renderingService, "renderingService");
    }

    @Override
    public Integer call() {
        final MintConfigRequest request = ioOptions
            .readPayload(payloadMapper, MintConfigRequest.class)
            .orElseGet(() -> MintConfigRequest.withDefaults(mintId));
        final MintConfigResponse response = configPort.applyConfiguration(request);
        spec.commandLine().getOut().println(renderingService.render(response, ioOptions.outputFormat()));
        return CommandLine.ExitCode.OK;
    }
}
