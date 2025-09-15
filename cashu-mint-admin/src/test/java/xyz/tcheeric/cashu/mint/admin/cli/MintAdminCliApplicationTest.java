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
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;

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

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort))
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

        final String payload = "{\"mintId\":\"mint-007\",\"parameters\":{\"rate\":\"5\"}}";

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort))
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

        final Path yamlFile = Files.createTempFile("mint-users", ".yml");
        Files.writeString(yamlFile, "mintId: yaml-mint\nincludeInactive: true\n");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort))
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

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configPort, usersPort, alertsPort))
            .execute("alerts", "--mint-id=alerts-mint", "--severity=WARN", "--output-format=JSON");

        final MintAlertsRequest request = alertsPort.lastRequest.get();
        assertThat(request.mintId()).isEqualTo("alerts-mint");
        assertThat(request.severity()).isEqualTo("WARN");
        assertThat(execution.getSystemOutString()).contains("\"alert-2\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    private CommandLine commandLine(final MintStatusPort statusPort,
                                    final MintConfigPort configPort,
                                    final MintUsersPort usersPort,
                                    final MintAlertsPort alertsPort) {
        final CommandPayloadMapper mapper = CommandPayloadMapper.createDefault();
        final ResponseRenderingService renderer = ResponseRenderingService.createDefault(mapper.jsonMapper());
        return MintAdminCliApplication.buildCommandLine(mapper, renderer, statusPort, configPort, usersPort, alertsPort);
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
}
