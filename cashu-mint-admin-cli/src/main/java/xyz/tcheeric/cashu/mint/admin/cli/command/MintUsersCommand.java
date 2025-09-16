package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUsersRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUserRecord;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "users",
         description = "Inspect operator accounts",
         mixinStandardHelpOptions = true)
public final class MintUsersCommand implements Callable<Integer> {

    private final MintUsersPort usersPort;
    private final CommandPayloadMapper payloadMapper;
    private final ResponseRenderingService renderingService;

    @Spec
    private CommandSpec spec;

    @Option(names = {"-m", "--mint-id"},
            description = "Mint identifier when no payload is supplied.",
            defaultValue = MintUsersRequest.DEFAULT_MINT_ID)
    private String mintId = MintUsersRequest.DEFAULT_MINT_ID;

    @Option(names = {"--include-inactive"},
            description = "Include inactive operators when no payload is supplied.",
            defaultValue = "false")
    private boolean includeInactive;

    @CommandLine.Mixin
    private final CommandIOOptions ioOptions = new CommandIOOptions();

    public MintUsersCommand(final MintUsersPort usersPort,
                            final CommandPayloadMapper payloadMapper,
                            final ResponseRenderingService renderingService) {
        this.usersPort = Objects.requireNonNull(usersPort, "usersPort");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.renderingService = Objects.requireNonNull(renderingService, "renderingService");
    }

    @Override
    public Integer call() {
        final MintUsersRequest request = ioOptions
            .readPayload(payloadMapper, MintUsersRequest.class)
            .orElseGet(() -> new MintUsersRequest(mintId, includeInactive));
        final List<MintUserRecord> users = usersPort.listUsers(request);
        spec.commandLine().getOut().println(renderingService.render(users, ioOptions.outputFormat()));
        return CommandLine.ExitCode.OK;
    }
}
