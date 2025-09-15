package xyz.tcheeric.cashu.mint.admin.cli;

import picocli.CommandLine;

import xyz.tcheeric.cashu.mint.admin.cli.command.MintAlertsCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintConfigCommand;
import xyz.tcheeric.cashu.mint.admin.cli.command.MintUsersCommand;
import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintAlertsPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintConfigPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintAlertsPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintConfigPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintUsersPort;

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
        return buildCommandLine(
            payloadMapper,
            renderingService,
            new StubMintStatusPort(),
            new StubMintConfigPort(),
            new StubMintUsersPort(),
            new StubMintAlertsPort()
        );
    }

    static CommandLine buildCommandLine(final CommandPayloadMapper payloadMapper,
                                        final ResponseRenderingService renderingService,
                                        final MintStatusPort statusPort,
                                        final MintConfigPort configPort,
                                        final MintUsersPort usersPort,
                                        final MintAlertsPort alertsPort) {
        final MintCommand root = new MintCommand(statusPort, payloadMapper, renderingService);
        final CommandLine commandLine = new CommandLine(root);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.addSubcommand("config", new MintConfigCommand(configPort, payloadMapper, renderingService));
        commandLine.addSubcommand("users", new MintUsersCommand(usersPort, payloadMapper, renderingService));
        commandLine.addSubcommand("alerts", new MintAlertsCommand(alertsPort, payloadMapper, renderingService));
        return commandLine;
    }
}
