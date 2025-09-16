package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryCliPresenter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;

abstract class MintLifecycleCommandSupport implements Callable<Integer> {

    private final LifecycleAction operation;
    private final MintLifecyclePort lifecyclePort;
    private final CommandPayloadMapper payloadMapper;
    private final LifecycleSummaryCliPresenter summaryPresenter;

    @Spec
    private CommandSpec spec;

    @CommandLine.Mixin
    private final CommandIOOptions ioOptions = new CommandIOOptions();

    @Option(names = "--mint-id",
            description = "Identifier of the mint to operate on.")
    private String mintId;

    @Option(names = "--operator-id",
            description = "Operator UUID authorising the lifecycle change.")
    private String operatorId;

    @Option(names = "--version-tag",
            description = "Version tag recorded with the lifecycle change.")
    private String versionTag;

    @Option(names = "--request-id",
            description = "Unique identifier for this lifecycle invocation.")
    private String requestId;

    @Option(names = "--correlation-id",
            description = "External correlation identifier linking related changes.")
    private String correlationId;

    @Option(names = {"-y", "--yes"},
            description = "Automatically confirm the lifecycle action.")
    private boolean autoConfirm;

    MintLifecycleCommandSupport(final LifecycleAction operation,
                                final MintLifecyclePort lifecyclePort,
                                final CommandPayloadMapper payloadMapper,
                                final LifecycleSummaryCliPresenter summaryPresenter) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.lifecyclePort = Objects.requireNonNull(lifecyclePort, "lifecyclePort");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.summaryPresenter = Objects.requireNonNull(summaryPresenter, "summaryPresenter");
    }

    @Override
    public final Integer call() {
        try {
            final MintLifecycleRequest request = resolveRequest();
            if (!autoConfirm && !confirm(request)) {
                spec.commandLine().getOut().println("Lifecycle command aborted by user.");
                return CommandLine.ExitCode.SOFTWARE;
            }
            final MintLifecyclePort.MintLifecycleCommand command =
                new MintLifecyclePort.MintLifecycleCommand(operation, request);
            final LifecycleSummary response = lifecyclePort.execute(command);
            final String rendered = summaryPresenter.present(response, ioOptions.outputFormat());
            spec.commandLine().getOut().println(rendered);
            if (response.idempotent()) {
                spec.commandLine().getErr().println("No changes applied (idempotent request).");
            }
            return CommandLine.ExitCode.OK;
        } catch (final IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(spec.commandLine(), ex.getMessage(), ex);
        }
    }

    private MintLifecycleRequest resolveRequest() {
        return ioOptions
            .readPayload(payloadMapper, MintLifecycleRequest.class)
            .map(this::ensureRequestIdentifiers)
            .orElseGet(() -> ensureRequestIdentifiers(buildFromOptions()));
    }

    private MintLifecycleRequest buildFromOptions() {
        if (mintId == null || operatorId == null || versionTag == null) {
            throw new IllegalArgumentException(
                "Lifecycle command requires --mint-id, --operator-id, and --version-tag when no payload is provided.");
        }
        return new MintLifecycleRequest(mintId, operatorId, versionTag, requestId, correlationId);
    }

    private boolean confirm(final MintLifecycleRequest request) {
        final PrintWriter out = spec.commandLine().getOut();
        out.printf("%s [y/N]: ", confirmationPrompt(request));
        out.flush();
        try {
            final BufferedReader reader = inputReader();
            final String line = reader.readLine();
            if (line == null) {
                return false;
            }
            final String trimmed = line.trim().toLowerCase();
            return "y".equals(trimmed) || "yes".equals(trimmed);
        } catch (final IOException ex) {
            throw new CommandLine.ExecutionException(spec.commandLine(), "Failed to read confirmation input", ex);
        }
    }

    protected abstract String confirmationPrompt(MintLifecycleRequest request);

    private BufferedReader inputReader() {
        final Reader consoleReader = System.console() != null ? System.console().reader() : null;
        if (consoleReader != null) {
            return new BufferedReader(consoleReader);
        }
        return new BufferedReader(new InputStreamReader(System.in));
    }

    private MintLifecycleRequest ensureRequestIdentifiers(final MintLifecycleRequest request) {
        final String resolvedRequestId = request.requestId() == null
            ? UUID.randomUUID().toString()
            : request.requestId();
        final String resolvedCorrelationId = request.correlationId() == null
            ? resolvedRequestId
            : request.correlationId();
        if (resolvedRequestId.equals(request.requestId()) && resolvedCorrelationId.equals(request.correlationId())) {
            return request;
        }
        return new MintLifecycleRequest(request.mintId(), request.operatorId(), request.versionTag(), resolvedRequestId,
            resolvedCorrelationId);
    }
}
