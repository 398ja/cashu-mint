package xyz.tcheeric.cashu.mint.admin.cli;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import xyz.tcheeric.cashu.mint.admin.cli.command.MintAlertsCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintConfigCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintCreateCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintPauseCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintResumeCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintRetireCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintUpdateCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintUsersCommand;
import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintAlertsPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintConfigPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintAlertsPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintConfigPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintUsersPort;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle.LifecycleSummaryCliPresenter;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;

/**
 * Picocli entry point for mint administration commands.
 */
public final class MintAdminCliApplication {

    private MintAdminCliApplication() {
    }

    public static void main(final String[] args) {
        final CommandLine commandLine = defaultCommandLine();
        final int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    static CommandLine defaultCommandLine() {
        final CommandPayloadMapper payloadMapper = CommandPayloadMapper.createDefault();
        final ResponseRenderingService renderingService = ResponseRenderingService.createDefault(payloadMapper.jsonMapper());
        final LifecycleSummaryCliPresenter lifecyclePresenter = new LifecycleSummaryCliPresenter(payloadMapper.jsonMapper());
        return buildCommandLine(
            payloadMapper,
            renderingService,
            lifecyclePresenter,
            new StubMintStatusPort(),
            new StubMintConfigPort(),
            new StubMintUsersPort(),
            new StubMintAlertsPort(),
            new StubMintLifecyclePort()
        );
    }

    static CommandLine buildCommandLine(final CommandPayloadMapper payloadMapper,
                                        final ResponseRenderingService renderingService,
                                        final LifecycleSummaryCliPresenter lifecyclePresenter,
                                        final MintStatusPort statusPort,
                                        final MintConfigPort configPort,
                                        final MintUsersPort usersPort,
                                        final MintAlertsPort alertsPort,
                                        final MintLifecyclePort lifecyclePort) {
        final MintCommand root = new MintCommand(statusPort, payloadMapper, renderingService);
        final CommandLine commandLine = new CommandLine(root);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.addSubcommand("config", new MintConfigCommand(configPort, payloadMapper, renderingService));
        commandLine.addSubcommand("users", new MintUsersCommand(usersPort, payloadMapper, renderingService));
        commandLine.addSubcommand("alerts", new MintAlertsCommand(alertsPort, payloadMapper, renderingService));
        commandLine.addSubcommand("create", new MintCreateCommand(lifecyclePort, payloadMapper, lifecyclePresenter));
        commandLine.addSubcommand("update", new MintUpdateCommand(lifecyclePort, payloadMapper, lifecyclePresenter));
        commandLine.addSubcommand("pause", new MintPauseCommand(lifecyclePort, payloadMapper, lifecyclePresenter));
        commandLine.addSubcommand("resume", new MintResumeCommand(lifecyclePort, payloadMapper, lifecyclePresenter));
        commandLine.addSubcommand("retire", new MintRetireCommand(lifecyclePort, payloadMapper, lifecyclePresenter));
        commandLine.setExecutionStrategy(MintAdminCliApplication::executeWithCorrelationId);
        return commandLine;
    }

    private static int executeWithCorrelationId(final ParseResult parseResult) {
        try (CorrelationIdContext.Scope ignored = CorrelationIdContext.open()) {
            return new CommandLine.RunLast().execute(parseResult);
        }
    }
}
