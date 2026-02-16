package xyz.tcheeric.cashu.mint.admin.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import xyz.tcheeric.cashu.mint.admin.cli.port.http.HttpMintLifecyclePort;
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

    private static final Set<String> BOOTSTRAP_OPTIONS = Set.of("--api-url", "--api-key");

    public static void main(final String[] args) {
        final String apiUrl = resolveOption(args, "--api-url");
        final String apiKey = resolveOption(args, "--api-key");
        final CommandLine commandLine = apiUrl != null
            ? httpCommandLine(apiUrl, apiKey != null ? apiKey : "")
            : defaultCommandLine();
        final String[] filteredArgs = stripBootstrapOptions(args);
        final int exitCode = commandLine.execute(filteredArgs);
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

    static CommandLine httpCommandLine(final String apiUrl, final String apiKey) {
        final CommandPayloadMapper payloadMapper = CommandPayloadMapper.createDefault();
        final ResponseRenderingService renderingService = ResponseRenderingService.createDefault(payloadMapper.jsonMapper());
        final LifecycleSummaryCliPresenter lifecyclePresenter = new LifecycleSummaryCliPresenter(payloadMapper.jsonMapper());
        final HttpMintLifecyclePort lifecyclePort = new HttpMintLifecyclePort(apiUrl, apiKey, payloadMapper.jsonMapper());
        return buildCommandLine(
            payloadMapper,
            renderingService,
            lifecyclePresenter,
            new StubMintStatusPort(),
            new StubMintConfigPort(),
            new StubMintUsersPort(),
            new StubMintAlertsPort(),
            lifecyclePort
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

    private static String[] stripBootstrapOptions(final String[] args) {
        final List<String> filtered = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            if (BOOTSTRAP_OPTIONS.contains(args[i]) && i + 1 < args.length) {
                i++; // skip the option value
                continue;
            }
            boolean isInlineBootstrap = false;
            for (final String opt : BOOTSTRAP_OPTIONS) {
                if (args[i].startsWith(opt + "=")) {
                    isInlineBootstrap = true;
                    break;
                }
            }
            if (!isInlineBootstrap) {
                filtered.add(args[i]);
            }
        }
        return filtered.toArray(String[]::new);
    }

    private static String resolveOption(final String[] args, final String optionName) {
        for (int i = 0; i < args.length - 1; i++) {
            if (optionName.equals(args[i])) {
                return args[i + 1];
            }
            if (args[i].startsWith(optionName + "=")) {
                return args[i].substring(optionName.length() + 1);
            }
        }
        if (args.length > 0 && args[args.length - 1].startsWith(optionName + "=")) {
            return args[args.length - 1].substring(optionName.length() + 1);
        }
        return null;
    }
}
