package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.service.ConfigurationValidationException;
import xyz.tcheeric.cashu.mint.admin.application.service.MissingApprovalException;
import xyz.tcheeric.cashu.mint.admin.application.service.RollbackConflictException;
import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowCliPresenter;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

abstract class ConfigurationWorkflowCliCommand<C extends ManageConfigurationUseCase.WorkflowCommand>
    implements Callable<Integer> {

    static final String DEFAULT_MINT_ID = "default-mint";

    private final ManageConfigurationUseCase configurationUseCase;
    private final CommandPayloadMapper payloadMapper;
    private final ConfigurationWorkflowCliPresenter presenter;

    @Spec
    private CommandSpec spec;

    @Mixin
    private final CommandIOOptions ioOptions = new CommandIOOptions();

    @Option(names = {"-m", "--mint-id"},
            description = "Identifier of the mint to target when the payload omits it.",
            defaultValue = DEFAULT_MINT_ID)
    private String mintId = DEFAULT_MINT_ID;

    @Option(names = "--operator-id",
            description = "Operator executing the workflow command.")
    private String operatorId;

    @Option(names = "--version-tag",
            description = "Version tag associated with the configuration revision.")
    private String versionTag;

    @Option(names = "--reason-code",
            description = "Business reason codes explaining the change. Repeatable.",
            split = ",")
    private List<String> reasonCodes = new ArrayList<>();

    @Option(names = "--ticket-reference",
            description = "External ticket references linked to the change. Repeatable.",
            split = ",")
    private List<String> ticketReferences = new ArrayList<>();

    @Option(names = "--request-id",
            description = "Audit request identifier supplied by the caller.")
    private String requestId;

    @Option(names = "--correlation-id",
            description = "Correlation identifier supplied by the caller.")
    private String correlationId;

    protected ConfigurationWorkflowCliCommand(final ManageConfigurationUseCase configurationUseCase,
                                              final CommandPayloadMapper payloadMapper,
                                              final ConfigurationWorkflowCliPresenter presenter) {
        this.configurationUseCase = Objects.requireNonNull(configurationUseCase, "configurationUseCase");
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
    }

    protected ManageConfigurationUseCase configurationUseCase() {
        return configurationUseCase;
    }

    protected CommandPayloadMapper payloadMapper() {
        return payloadMapper;
    }

    protected CommandSpec spec() {
        return spec;
    }

    protected CommandIOOptions ioOptions() {
        return ioOptions;
    }

    protected String optionMintId() {
        return mintId;
    }

    protected String optionOperatorId() {
        return operatorId;
    }

    protected String optionVersionTag() {
        return versionTag;
    }

    protected List<String> optionReasonCodes() {
        return reasonCodes;
    }

    protected List<String> optionTicketReferences() {
        return ticketReferences;
    }

    protected String optionRequestId() {
        return requestId;
    }

    protected String optionCorrelationId() {
        return correlationId;
    }

    protected <T> Optional<T> readPayload(final Class<T> type) {
        return ioOptions.readPayload(payloadMapper, type);
    }

    @Override
    public final Integer call() {
        try {
            final ManageConfigurationUseCase.ConfigurationWorkflowResponse response = invoke();
            spec.commandLine().getOut().println(presenter.present(response, ioOptions.outputFormat()));
            return CommandLine.ExitCode.OK;
        } catch (final AbortedExecutionException ex) {
            spec.commandLine().getOut().println(ex.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        } catch (final ConfigurationValidationException ex) {
            spec.commandLine().getErr().println(ex.getMessage());
            if (ex.response() != null) {
                spec.commandLine().getOut().println(presenter.present(ex.response(), ioOptions.outputFormat()));
            }
            return CommandLine.ExitCode.SOFTWARE;
        } catch (final MissingApprovalException | RollbackConflictException ex) {
            spec.commandLine().getErr().println(ex.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    protected final void requireConfirmation(final boolean autoConfirm,
                                              final String prompt,
                                              final String abortMessage) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(abortMessage, "abortMessage");
        if (autoConfirm) {
            return;
        }
        if (!confirm(prompt)) {
            throw new AbortedExecutionException(abortMessage);
        }
    }

    protected abstract ManageConfigurationUseCase.ConfigurationWorkflowResponse invoke();

    protected String coalesce(final String optionValue,
                              final String payloadValue,
                              final Supplier<String> fallbackSupplier) {
        final String option = normalise(optionValue);
        if (option != null) {
            return option;
        }
        final String payload = normalise(payloadValue);
        if (payload != null) {
            return payload;
        }
        return fallbackSupplier == null ? null : fallbackSupplier.get();
    }

    protected List<String> coalesceList(final List<String> optionValues,
                                        final List<String> payloadValues) {
        final List<String> option = normalise(optionValues);
        if (!option.isEmpty()) {
            return option;
        }
        final List<String> payload = normalise(payloadValues);
        if (!payload.isEmpty()) {
            return payload;
        }
        return List.of();
    }

    protected String requireNonBlank(final String value, final String parameterDescription) {
        if (value == null || value.isBlank()) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                parameterDescription + " must be provided either via options or payload.");
        }
        return value;
    }

    protected ManageConfigurationUseCase.ConfigurationPayload defaultPayload() {
        return new ManageConfigurationUseCase.ConfigurationPayload(Map.of(), Map.of(), "");
    }

    protected String defaultCorrelationId() {
        final String current = CorrelationIdContext.currentId();
        return current != null ? current : UUID.randomUUID().toString();
    }

    protected String defaultRequestId() {
        return UUID.randomUUID().toString();
    }

    private String normalise(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> normalise(final List<String> values) {
        if (values == null) {
            return List.of();
        }
        final List<String> cleaned = values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.copyOf(cleaned);
    }

    private boolean confirm(final String prompt) {
        final PrintWriter out = spec.commandLine().getOut();
        out.printf("%s [y/N]: ", prompt);
        out.flush();
        try {
            final BufferedReader reader = inputReader();
            final String line = reader.readLine();
            if (line == null) {
                return false;
            }
            final String trimmed = line.trim().toLowerCase(Locale.ROOT);
            return "y".equals(trimmed) || "yes".equals(trimmed);
        } catch (final IOException ex) {
            throw new CommandLine.ExecutionException(spec.commandLine(), "Failed to read confirmation input", ex);
        }
    }

    private BufferedReader inputReader() {
        final Reader consoleReader = System.console() != null ? System.console().reader() : null;
        if (consoleReader != null) {
            return new BufferedReader(consoleReader);
        }
        return new BufferedReader(new InputStreamReader(System.in));
    }

    private static final class AbortedExecutionException extends RuntimeException {
        AbortedExecutionException(final String message) {
            super(message);
        }
    }
}
