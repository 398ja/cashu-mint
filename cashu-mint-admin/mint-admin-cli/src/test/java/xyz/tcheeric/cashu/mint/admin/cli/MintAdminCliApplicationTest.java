package xyz.tcheeric.cashu.mint.admin.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.test.Execution;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertsRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigResponse;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusResponse;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUserRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUsersRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintAlertsPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintConfigPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle.LifecycleSummaryCliPresenter;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MintAdminCliApplicationTest {

    // Ensures the top-level mint command invokes the status port when no payload is supplied.
    @Test
    void shouldInvokeMintStatusPortWithDefaultRequest() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort, lifecyclePort))
            .execute("--output-format=JSON");

        assertThat(statusPort.lastRequest.get()).isEqualTo(MintStatusRequest.defaultRequest());
        assertThat(execution.getSystemOutString()).contains("\"mintId\" : \"mint-123\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Verifies JSON payloads are parsed for the mint config command and dispatched to the port.
    @Test
    void shouldParseJsonPayloadForConfigCommand() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final String payload = "{\"mintId\":\"mint-007\",\"parameters\":{\"rate\":\"5\"}}";

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort, lifecyclePort))
            .execute("config", "--payload=" + payload, "--output-format=JSON");

        final MintConfigRequest request = configPort.lastRequest.get();
        assertThat(request.mintId()).isEqualTo("mint-007");
        assertThat(request.parameters()).containsEntry("rate", "5");
        assertThat(execution.getSystemOutString()).contains("\"revision\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Confirms YAML payloads can be supplied via file for the users command.
    @Test
    void shouldParseYamlPayloadForUsersCommand() throws IOException {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Path yamlFile = Files.createTempFile("mint-users", ".yml");
        Files.writeString(yamlFile, "mintId: yaml-mint\nincludeInactive: true\n");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort, lifecyclePort))
            .execute("users", "--input-format=YAML", "--payload-file=" + yamlFile.toAbsolutePath());

        final MintUsersRequest request = usersPort.lastRequest.get();
        assertThat(request.mintId()).isEqualTo("yaml-mint");
        assertThat(request.includeInactive()).isTrue();
        assertThat(execution.getSystemOutString()).contains("operator-1");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures option defaults populate the alerts request when no payload is given.
    @Test
    void shouldUseOptionsWhenAlertsPayloadMissing() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort,
                lifecyclePort))
            .execute("alerts", "--mint-id=alerts-mint", "--severity=WARN", "--output-format=JSON");

        final MintAlertsRequest request = alertsPort.lastRequest.get();
        assertThat(request.mintId()).isEqualTo("alerts-mint");
        assertThat(request.severity()).isEqualTo("WARN");
        assertThat(execution.getSystemOutString()).contains("\"alert-2\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Validates JSON lifecycle payloads execute the create command and emit machine-readable output.
    @Test
    void shouldExecuteCreateLifecycleCommandWithJsonPayload() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final String payload = "{\"mintId\":\"mint-321\",\"operatorId\":\"123e4567-e89b-12d3-a456-426614174000\",\"versionTag\":\"v1.0.0\"}";

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort,
                lifecyclePort))
            .execute("create", "--payload=" + payload, "--output-format=JSON", "--yes");

        final MintLifecyclePort.MintLifecycleCommand command = lifecyclePort.lastCommand.get();
        assertThat(command.operation()).isEqualTo(LifecycleAction.CREATE);
        assertThat(command.request().mintId()).isEqualTo("mint-321");
        assertThat(execution.getSystemOutString()).contains("\"currentState\" : \"PROVISIONING\"");
        assertThat(execution.getSystemOutString()).contains("\"changed\" : true");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures lifecycle commands flag idempotency when the target state is already applied.
    @Test
    void shouldIndicateIdempotentPauseLifecycleCommand() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();
        lifecyclePort.seed("mint-777", LifecycleState.State.SUSPENDED, "v1");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort,
                lifecyclePort))
            .execute("pause", "--mint-id=mint-777", "--operator-id=123e4567-e89b-12d3-a456-426614174000",
                "--version-tag=v1", "--output-format=JSON", "--yes");

        assertThat(execution.getSystemOutString()).contains("\"changed\" : false");
        assertThat(execution.getSystemOutString()).contains("already suspended");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures lifecycle commands run with a generated correlation identifier.
    @Test
    void shouldPopulateCorrelationIdDuringLifecycleExecution() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingMintConfigPort configPort = new RecordingMintConfigPort();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();
        lifecyclePort.seed("mint-200", LifecycleState.State.ACTIVE, "v1");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort,
                lifecyclePort))
            .execute("pause", "--mint-id=mint-200", "--operator-id=123e4567-e89b-12d3-a456-426614174000",
                "--version-tag=v1", "--output-format=JSON", "--yes");

        assertThat(lifecyclePort.lastCorrelationId.get()).isNotBlank();
        assertThat(CorrelationIdContext.currentId()).isNull();
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    private CommandLine commandLine(final MintStatusPort statusPort,
                                    final MintConfigPort configPort,
                                    final MintUsersPort usersPort,
                                    final MintAlertsPort alertsPort,
                                    final MintLifecyclePort lifecyclePort) {
        final CommandPayloadMapper mapper = CommandPayloadMapper.createDefault();
        final ResponseRenderingService renderer = ResponseRenderingService.createDefault(mapper.jsonMapper());
        final LifecycleSummaryCliPresenter lifecyclePresenter = new LifecycleSummaryCliPresenter(mapper.jsonMapper());
        return MintAdminCliApplication.buildCommandLine(mapper, renderer, lifecyclePresenter, statusPort, configPort, usersPort,
            alertsPort, lifecyclePort);
    }

    private static final class RecordingMintStatusPort implements MintStatusPort {
        private final AtomicReference<MintStatusRequest> lastRequest = new AtomicReference<>();

        @Override
        public MintStatusResponse fetchStatus(final MintStatusRequest request) {
            lastRequest.set(request);
            return new MintStatusResponse("mint-123", "ACTIVE", 2, 0);
        }
    }

    private static final class RecordingMintConfigPort implements MintConfigPort {
        private final AtomicReference<MintConfigRequest> lastRequest = new AtomicReference<>();

        @Override
        public MintConfigResponse applyConfiguration(final MintConfigRequest request) {
            lastRequest.set(request);
            return new MintConfigResponse(request.mintId(), "revision-1", request.parameters());
        }
    }

    private static final class RecordingMintUsersPort implements MintUsersPort {
        private final AtomicReference<MintUsersRequest> lastRequest = new AtomicReference<>();

        @Override
        public List<MintUserRecord> listUsers(final MintUsersRequest request) {
            lastRequest.set(request);
            return List.of(
                new MintUserRecord("operator-1", "Alice", "ADMIN", true),
                new MintUserRecord("operator-2", "Bob", "AUDITOR", request.includeInactive())
            );
        }
    }

    private static final class RecordingMintAlertsPort implements MintAlertsPort {
        private final AtomicReference<MintAlertsRequest> lastRequest = new AtomicReference<>();

        @Override
        public List<MintAlertRecord> listAlerts(final MintAlertsRequest request) {
            lastRequest.set(request);
            return List.of(
                new MintAlertRecord("alert-1", "INFO", "Info alert", OffsetDateTime.now().minusHours(6).toString()),
                new MintAlertRecord("alert-2", "WARN", "Warning alert", OffsetDateTime.now().minusHours(1).toString())
            );
        }
    }

    private static final class RecordingMintLifecyclePort extends StubMintLifecyclePort {
        private final AtomicReference<MintLifecyclePort.MintLifecycleCommand> lastCommand = new AtomicReference<>();
        private final AtomicReference<String> lastCorrelationId = new AtomicReference<>();

        @Override
        public LifecycleSummary execute(final MintLifecycleCommand command) {
            lastCommand.set(command);
            lastCorrelationId.set(CorrelationIdContext.currentId());
            return super.execute(command);
        }
    }
}
