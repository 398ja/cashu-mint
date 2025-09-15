package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertsRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintAlertsPort;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "alerts",
         description = "Inspect mint alerts",
         mixinStandardHelpOptions = true)
public final class MintAlertsCommand implements Callable<Integer> {

    private final MintAlertsPort alertsPort;
    private final CommandPayloadMapper payloadMapper;
    private final ResponseRenderingService renderingService;

    @Spec
    private CommandSpec spec;

    @Option(names = {"-m", "--mint-id"},
            description = "Mint identifier when no payload is supplied.",
            defaultValue = MintAlertsRequest.DEFAULT_MINT_ID)
    private String mintId = MintAlertsRequest.DEFAULT_MINT_ID;

    @Option(names = {"-s", "--severity"},
            description = "Minimum severity filter when no payload is supplied.",
            defaultValue = MintAlertsRequest.DEFAULT_SEVERITY)
    private String severity = MintAlertsRequest.DEFAULT_SEVERITY;

    @CommandLine.Mixin
    private final CommandIOOptions ioOptions = new CommandIOOptions();

    public MintAlertsCommand(final MintAlertsPort alertsPort,
                             final CommandPayloadMapper payloadMapper,
                             final ResponseRenderingService renderingService) {
        this.alertsPort = Objects.requireNonNull(alertsPort, "alertsPort");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.renderingService = Objects.requireNonNull(renderingService, "renderingService");
    }

    @Override
    public Integer call() {
        final MintAlertsRequest request = ioOptions
            .readPayload(payloadMapper, MintAlertsRequest.class)
            .orElseGet(() -> new MintAlertsRequest(mintId, severity));
        final List<MintAlertRecord> alerts = alertsPort.listAlerts(request);
        spec.commandLine().getOut().println(renderingService.render(alerts, ioOptions.outputFormat()));
        return CommandLine.ExitCode.OK;
    }
}
