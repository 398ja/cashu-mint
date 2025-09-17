package xyz.tcheeric.cashu.mint.admin.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.test.Execution;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.ResponseRenderingService;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertsRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintStatusResponse;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUserRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUsersRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintAlertsPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintStatusPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.configuration.ConfigurationWorkflowCliPresenter;
import xyz.tcheeric.cashu.mint.admin.cli.presentation.lifecycle.LifecycleSummaryCliPresenter;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.service.ConfigurationValidationException;
import xyz.tcheeric.cashu.mint.admin.application.service.MissingApprovalException;
import xyz.tcheeric.cashu.mint.admin.application.service.RollbackConflictException;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.ValidationReport;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MintAdminCliApplicationTest {

    private static final String VALID_MINT_UUID = "123e4567-e89b-12d3-a456-426614174000";

    // Ensures the top-level mint command invokes the status port when no payload is supplied.
    @Test
    void shouldInvokeMintStatusPortWithDefaultRequest() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort, lifecyclePort))
            .execute("--output-format=JSON");

        assertThat(statusPort.lastRequest.get()).isEqualTo(MintStatusRequest.defaultRequest());
        assertThat(execution.getSystemOutString()).contains("\"mintId\" : \"mint-123\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Verifies JSON payloads are parsed for the mint config command and dispatched to the port.
    @Test
    void shouldParseJsonPayloadForConfigCommand() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final String payload = "{\"payload\":{\"parameters\":{\"rate\":{\"value\":\"5\"}},\"metadata\":{\"region\":\"EU\"}," +
            "\"summary\":\"Update rate\"},\"approvalChecklist\":[\"payload-check\"],\"reasonCodes\":[\"payload-reason\"]," +
            "\"ticketReferences\":[\"payload-ticket\"],\"requestId\":\"payload-request\",\"correlationId\":\"payload-correlation\"}";

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort, lifecyclePort))
            .execute("config", "submit", "--mint-id=option-mint", "--operator-id=operator-001", "--version-tag=v2",
                "--reason-code=RC-1", "--ticket-reference=TCK-9", "--payload=" + payload, "--output-format=JSON");

        final ManageConfigurationUseCase.SubmitConfigurationCommand command = configUseCase.lastSubmitCommand();
        assertThat(command.mintId()).isEqualTo("option-mint");
        assertThat(command.operatorId()).isEqualTo("operator-001");
        assertThat(command.versionTag()).isEqualTo("v2");
        assertThat(command.payload().parameters()).containsKey("rate");
        assertThat(command.approvalChecklist()).contains("payload-check");
        assertThat(command.reasonCodes()).containsExactly("RC-1");
        assertThat(command.ticketReferences()).containsExactly("TCK-9");
        assertThat(command.requestId()).isEqualTo("payload-request");
        assertThat(command.correlationId()).isEqualTo("payload-correlation");
        assertThat(execution.getSystemOutString()).contains("\"mintId\" : \"option-mint\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Confirms submit responses render diff and validation data for workflow visibility.
    @Test
    void shouldRenderSubmitWorkflowWithDiffAndValidationSections() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "submit", "--mint-id=mint-visual", "--operator-id=operator-visual", "--output-format=JSON");

        assertThat(configUseCase.lastSubmitCommand()).isNotNull();
        assertThat(execution.getSystemOutString())
            .contains("\"diff\"")
            .contains("\"validation\"")
            .contains("\"validator\" : \"stub-validator\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures validation failures during submit surface error output and workflow context.
    @Test
    void shouldReportValidationErrorDuringSubmit() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final SubmitValidationFailureUseCase configUseCase = new SubmitValidationFailureUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "submit", "--mint-id=" + VALID_MINT_UUID, "--operator-id=operator-validator",
                "--output-format=JSON");

        execution.assertExitCode(CommandLine.ExitCode.SOFTWARE);
        assertThat(execution.getSystemErrString()).contains("Validation failed for configuration revision");
        assertThat(execution.getSystemOutString())
            .contains("\"diff\"")
            .contains("\"validation\"");
        assertThat(configUseCase.lastSubmitCommand()).isNotNull();
    }

    // Ensures workflow presenters redact secret material from rendered output.
    @Test
    void shouldRedactSecretsInConfigurationOutput() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final LeakyManageConfigurationUseCase configUseCase = new LeakyManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "preview", "--mint-id=mint-999", "--operator-id=operator-999",
                "--target-revision=rev-999", "--include-validation", "--output-format=JSON");

        assertThat(execution.getSystemOutString()).doesNotContain("super-secret");
        assertThat(execution.getSystemOutString()).contains("***");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Verifies preview responses surface diff and validation details for operator review.
    @Test
    void shouldRenderPreviewWorkflowWithDiffAndValidationDetails() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "preview", "--mint-id=mint-preview", "--operator-id=operator-preview",
                "--target-revision=rev-321", "--include-validation", "--output-format=JSON");

        assertThat(configUseCase.lastPreviewCommand()).isNotNull();
        assertThat(execution.getSystemOutString())
            .contains("\"diff\"")
            .contains("\"validation\"")
            .contains("\"artefacts\"")
            .contains("\"latencyProfile\" : \"PASS\"")
            .contains("\"throughputCheck\" : \"WARN\"")
            .contains("\"validator\" : \"stub-validator\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures preview exits with usage guidance when the target revision is not supplied.
    @Test
    void shouldRequireTargetRevisionForPreviewCommand() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "preview", "--mint-id=mint-preview", "--operator-id=operator-preview");

        execution.assertExitCode(CommandLine.ExitCode.USAGE);
        assertThat(configUseCase.lastPreviewCommand()).isNull();
        assertThat(execution.getSystemErrString()).contains("--target-revision");
    }

    // Ensures validation failures during preview display workflow context for diagnosis.
    @Test
    void shouldReportValidationErrorDuringPreview() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final PreviewValidationFailureUseCase configUseCase = new PreviewValidationFailureUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "preview", "--mint-id=" + VALID_MINT_UUID, "--operator-id=operator-preview",
                "--target-revision=rev-404", "--include-validation", "--output-format=JSON");

        execution.assertExitCode(CommandLine.ExitCode.SOFTWARE);
        assertThat(execution.getSystemErrString()).contains("Validation failed for configuration revision");
        assertThat(execution.getSystemOutString())
            .contains("\"diff\"")
            .contains("\"validation\"");
    }

    // Ensures the apply subcommand maps CLI options to the manage configuration use case.
    @Test
    void shouldConstructApplyConfigurationCommandFromOptions() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort, lifecyclePort))
            .execute("config", "apply", "--mint-id=mint-444", "--operator-id=operator-444", "--target-revision=rev-123",
                "--deployment-ticket=DEP-555", "--version-tag=v1.2.3", "--reason-code=CHG-001",
                "--ticket-reference=TCK-77", "--request-id=req-abc", "--correlation-id=cid-xyz", "--output-format=JSON", "--yes");

        final ManageConfigurationUseCase.ApplyConfigurationCommand command = configUseCase.lastApplyCommand();
        assertThat(command.mintId()).isEqualTo("mint-444");
        assertThat(command.operatorId()).isEqualTo("operator-444");
        assertThat(command.targetRevision()).isEqualTo("rev-123");
        assertThat(command.deploymentTicket()).isEqualTo("DEP-555");
        assertThat(command.versionTag()).isEqualTo("v1.2.3");
        assertThat(command.reasonCodes()).containsExactly("CHG-001");
        assertThat(command.ticketReferences()).containsExactly("TCK-77");
        assertThat(command.requestId()).isEqualTo("req-abc");
        assertThat(command.correlationId()).isEqualTo("cid-xyz");
        assertThat(execution.getSystemOutString())
            .contains("\"nextAction\"")
            .contains("\"diff\"")
            .contains("\"validation\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures apply commands prompt for confirmation when --yes is not provided.
    @Test
    void shouldAbortApplyWhenConfirmationRejected() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
        try {
            final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                    lifecyclePort))
                .execute("config", "apply", "--mint-id=mint-555", "--operator-id=operator-555", "--target-revision=rev-555",
                    "--deployment-ticket=DEP-777", "--output-format=JSON");

            execution.assertExitCode(CommandLine.ExitCode.SOFTWARE);
            assertThat(execution.getSystemOutString()).contains("Apply command aborted by user.");
            assertThat(configUseCase.lastApplyCommand()).isNull();
        } finally {
            System.setIn(originalIn);
        }
    }

    // Ensures apply surfaces missing approval errors to the operator.
    @Test
    void shouldReportMissingApprovalDuringApply() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final MissingApprovalManageConfigurationUseCase configUseCase = new MissingApprovalManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "apply", "--mint-id=" + VALID_MINT_UUID, "--operator-id=operator-approval",
                "--target-revision=rev-910", "--deployment-ticket=DEP-910", "--output-format=JSON", "--yes");

        execution.assertExitCode(CommandLine.ExitCode.SOFTWARE);
        assertThat(execution.getSystemErrString()).contains("Missing approval for configuration revision");
        assertThat(execution.getSystemOutString().trim()).isEmpty();
    }

    // Validates required options are enforced for the apply subcommand.
    @Test
    void shouldRequireTargetRevisionForApplyCommand() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort, lifecyclePort))
            .execute("config", "apply", "--mint-id=mint-444", "--operator-id=operator-444", "--deployment-ticket=DEP-555");

        execution.assertExitCode(CommandLine.ExitCode.USAGE);
        assertThat(configUseCase.lastApplyCommand()).isNull();
        assertThat(execution.getSystemErrString()).contains("--target-revision");
    }

    // Verifies rollback responses include diff, validation, and rollback metadata.
    @Test
    void shouldExecuteRollbackCommandAndRenderWorkflowDetails() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "rollback", "--mint-id=mint-rollback", "--operator-id=operator-rollback",
                "--target-revision=rev-777", "--rollback-reason=Investigate incident", "--audit-reference=AUD-9",
                "--output-format=JSON", "--yes");

        final ManageConfigurationUseCase.RollbackConfigurationCommand command = configUseCase.lastRollbackCommand();
        assertThat(command.mintId()).isEqualTo("mint-rollback");
        assertThat(command.operatorId()).isEqualTo("operator-rollback");
        assertThat(command.targetRevision()).isEqualTo("rev-777");
        assertThat(command.rollbackReason()).isEqualTo("Investigate incident");
        assertThat(command.auditReference()).isEqualTo("AUD-9");
        assertThat(execution.getSystemOutString())
            .contains("\"diff\"")
            .contains("\"validation\"")
            .contains("\"rollback\"")
            .contains("\"reason\" : \"Manual rollback\"");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures rollback commands honour confirmation prompts before executing.
    @Test
    void shouldAbortRollbackWhenConfirmationRejected() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
        try {
            final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                    lifecyclePort))
                .execute("config", "rollback", "--mint-id=mint-rollback", "--operator-id=operator-rollback",
                    "--target-revision=rev-888", "--rollback-reason=Testing confirmation", "--output-format=JSON");

            execution.assertExitCode(CommandLine.ExitCode.SOFTWARE);
            assertThat(execution.getSystemOutString()).contains("Rollback command aborted by user.");
            assertThat(configUseCase.lastRollbackCommand()).isNull();
        } finally {
            System.setIn(originalIn);
        }
    }

    // Ensures rollback conflict errors are surfaced without rendering stale workflow output.
    @Test
    void shouldReportRollbackConflictError() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RollbackConflictManageConfigurationUseCase configUseCase = new RollbackConflictManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("config", "rollback", "--mint-id=" + VALID_MINT_UUID, "--operator-id=operator-rollback",
                "--target-revision=rev-999", "--rollback-reason=Conflict detected", "--output-format=JSON", "--yes");

        execution.assertExitCode(CommandLine.ExitCode.SOFTWARE);
        assertThat(execution.getSystemErrString()).contains("Rollback conflict detected");
        assertThat(execution.getSystemOutString().trim()).isEmpty();
    }

    // Confirms YAML payloads can be supplied via file for the users command.
    @Test
    void shouldParseYamlPayloadForUsersCommand() throws IOException {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Path yamlFile = Files.createTempFile("mint-users", ".yml");
        Files.writeString(yamlFile, "mintId: yaml-mint\nincludeInactive: true\n");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort, lifecyclePort))
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
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
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
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();

        final String payload = "{\"mintId\":\"mint-321\",\"operatorId\":\"123e4567-e89b-12d3-a456-426614174000\",\"versionTag\":\"v1.0.0\"}";

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("create", "--payload=" + payload, "--output-format=JSON", "--yes");

        final MintLifecyclePort.MintLifecycleCommand command = lifecyclePort.lastCommand.get();
        assertThat(command.operation()).isEqualTo(LifecycleAction.CREATE);
        assertThat(command.request().mintId()).isEqualTo("mint-321");
        assertThat(execution.getSystemOutString()).contains("\"currentState\" : \"PROVISIONED\"");
        assertThat(execution.getSystemOutString()).contains("\"changed\" : true");
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    // Ensures lifecycle commands flag idempotency when the target state is already applied.
    @Test
    void shouldIndicateIdempotentPauseLifecycleCommand() {
        final RecordingMintStatusPort statusPort = new RecordingMintStatusPort();
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();
        lifecyclePort.seed("mint-777", LifecycleState.State.SUSPENDED, "v1");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
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
        final RecordingManageConfigurationUseCase configUseCase = new RecordingManageConfigurationUseCase();
        final RecordingMintUsersPort usersPort = new RecordingMintUsersPort();
        final RecordingMintAlertsPort alertsPort = new RecordingMintAlertsPort();
        final RecordingMintLifecyclePort lifecyclePort = new RecordingMintLifecyclePort();
        lifecyclePort.seed("mint-200", LifecycleState.State.ACTIVE, "v1");

        final Execution execution = Execution.builder(() -> commandLine(statusPort, configUseCase, usersPort, alertsPort,
                lifecyclePort))
            .execute("pause", "--mint-id=mint-200", "--operator-id=123e4567-e89b-12d3-a456-426614174000",
                "--version-tag=v1", "--output-format=JSON", "--yes");

        assertThat(lifecyclePort.lastCorrelationId.get()).isNotBlank();
        assertThat(CorrelationIdContext.currentId()).isNull();
        execution.assertExitCode(CommandLine.ExitCode.OK);
    }

    private CommandLine commandLine(final MintStatusPort statusPort,
                                    final ManageConfigurationUseCase configurationUseCase,
                                    final MintUsersPort usersPort,
                                    final MintAlertsPort alertsPort,
                                    final MintLifecyclePort lifecyclePort) {
        final CommandPayloadMapper mapper = CommandPayloadMapper.createDefault();
        final ResponseRenderingService renderer = ResponseRenderingService.createDefault(mapper.jsonMapper());
        final LifecycleSummaryCliPresenter lifecyclePresenter = new LifecycleSummaryCliPresenter(mapper.jsonMapper());
        final ConfigurationWorkflowCliPresenter configurationPresenter =
            new ConfigurationWorkflowCliPresenter(mapper.jsonMapper());
        return MintAdminCliApplication.buildCommandLine(mapper, renderer, lifecyclePresenter, configurationPresenter, statusPort,
            configurationUseCase, usersPort, alertsPort, lifecyclePort);
    }

    private static ConfigurationValidationException validationFailure(final String mintId,
                                                                       final ManageConfigurationUseCase.ConfigurationWorkflowResponse response) {
        final Instant timestamp = Instant.parse("2024-02-01T00:00:00Z");
        final ConfigurationRevisionId revisionId = ConfigurationRevisionId.of(99);
        final ValidationReport report = ValidationReport.failure(
            revisionId,
            List.of("Latency threshold exceeded"),
            new AuditMetadata("validator-bot", "VALIDATE", timestamp),
            timestamp,
            Map.of("latencyProfile", "FAIL", "throughputCheck", "FAIL")
        );
        return new ConfigurationValidationException(MintId.fromString(mintId), revisionId, report, response);
    }

    private static final class RecordingMintStatusPort implements MintStatusPort {
        private final AtomicReference<MintStatusRequest> lastRequest = new AtomicReference<>();

        @Override
        public MintStatusResponse fetchStatus(final MintStatusRequest request) {
            lastRequest.set(request);
            return new MintStatusResponse("mint-123", "ACTIVE", 2, 0);
        }
    }

    private static final class LeakyManageConfigurationUseCase extends StubManageConfigurationUseCase {
        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse preview(
            final ManageConfigurationUseCase.PreviewConfigurationCommand command) {
            final Instant now = Instant.parse("2024-01-01T00:00:00Z");
            final Map<String, ManageConfigurationUseCase.ConfigurationValueDto> secrets = Map.of(
                "apiKey", new ManageConfigurationUseCase.ConfigurationValueDto("super-secret", true, "aws/preview/ref")
            );
            final ManageConfigurationUseCase.ConfigurationSnapshot requested = new ManageConfigurationUseCase.ConfigurationSnapshot(
                command.targetRevision(), secrets, now, command.versionTag());
            final ManageConfigurationUseCase.ConfigurationSnapshot active = new ManageConfigurationUseCase.ConfigurationSnapshot(
                "rev-active", Map.of(), now.minusSeconds(3_600), "v-current");
            final ManageConfigurationUseCase.DiffSummary diffSummary = new ManageConfigurationUseCase.DiffSummary(1, 0, 0);
            final ManageConfigurationUseCase.DiffArtefact diffArtefact = new ManageConfigurationUseCase.DiffArtefact(secrets, Map.of(), Map.of());
            final ManageConfigurationUseCase.ValidationSummary validationSummary =
                new ManageConfigurationUseCase.ValidationSummary(true, true, List.of(), Map.of(), "leaky-validator", now);
            final ManageConfigurationUseCase.AuditSummary audit = new ManageConfigurationUseCase.AuditSummary(
                command.operatorId(),
                "PREVIEW",
                now,
                List.of(),
                List.of(),
                new ManageConfigurationUseCase.AuditReference(command.requestId(), command.correlationId()),
                new ManageConfigurationUseCase.AutomationDescriptor(false, "cli-stub", null)
            );
            return new ManageConfigurationUseCase.ConfigurationWorkflowResponse(
                command.mintId(),
                command.targetRevision(),
                active.revisionId(),
                command.versionTag(),
                ConfigurationRevisionState.VALIDATED,
                requested,
                active,
                diffSummary,
                diffArtefact,
                validationSummary,
                null,
                new ManageConfigurationUseCase.ApprovalChecklist(List.of(), List.of(), List.of()),
                List.of(),
                audit,
                List.of(),
                null,
                ManageConfigurationUseCase.NextAction.APPLY
            );
        }
    }

    private static class RecordingManageConfigurationUseCase extends StubManageConfigurationUseCase {
        private final AtomicReference<ManageConfigurationUseCase.SubmitConfigurationCommand> lastSubmitCommand = new AtomicReference<>();
        private final AtomicReference<ManageConfigurationUseCase.PreviewConfigurationCommand> lastPreviewCommand = new AtomicReference<>();
        private final AtomicReference<ManageConfigurationUseCase.ApplyConfigurationCommand> lastApplyCommand = new AtomicReference<>();
        private final AtomicReference<ManageConfigurationUseCase.RollbackConfigurationCommand> lastRollbackCommand = new AtomicReference<>();

        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse submit(final SubmitConfigurationCommand command) {
            lastSubmitCommand.set(command);
            return super.submit(command);
        }

        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse preview(final PreviewConfigurationCommand command) {
            lastPreviewCommand.set(command);
            return super.preview(command);
        }

        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse apply(final ApplyConfigurationCommand command) {
            lastApplyCommand.set(command);
            return super.apply(command);
        }

        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse rollback(final RollbackConfigurationCommand command) {
            lastRollbackCommand.set(command);
            return super.rollback(command);
        }

        ManageConfigurationUseCase.SubmitConfigurationCommand lastSubmitCommand() {
            return lastSubmitCommand.get();
        }

        ManageConfigurationUseCase.PreviewConfigurationCommand lastPreviewCommand() {
            return lastPreviewCommand.get();
        }

        ManageConfigurationUseCase.ApplyConfigurationCommand lastApplyCommand() {
            return lastApplyCommand.get();
        }

        ManageConfigurationUseCase.RollbackConfigurationCommand lastRollbackCommand() {
            return lastRollbackCommand.get();
        }
    }

    private static final class SubmitValidationFailureUseCase extends RecordingManageConfigurationUseCase {
        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse submit(final SubmitConfigurationCommand command) {
            final ManageConfigurationUseCase.ConfigurationWorkflowResponse response = super.submit(command);
            throw validationFailure(command.mintId(), response);
        }
    }

    private static final class PreviewValidationFailureUseCase extends RecordingManageConfigurationUseCase {
        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse preview(final PreviewConfigurationCommand command) {
            final ManageConfigurationUseCase.ConfigurationWorkflowResponse response = super.preview(command);
            throw validationFailure(command.mintId(), response);
        }
    }

    private static final class MissingApprovalManageConfigurationUseCase extends RecordingManageConfigurationUseCase {
        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse apply(final ApplyConfigurationCommand command) {
            super.apply(command);
            throw new MissingApprovalException(MintId.fromString(command.mintId()), ConfigurationRevisionId.of(101));
        }
    }

    private static final class RollbackConflictManageConfigurationUseCase extends RecordingManageConfigurationUseCase {
        @Override
        public ManageConfigurationUseCase.ConfigurationWorkflowResponse rollback(final RollbackConfigurationCommand command) {
            super.rollback(command);
            throw new RollbackConflictException(MintId.fromString(command.mintId()), ConfigurationRevisionId.of(202),
                "Rollback conflict detected", new IllegalStateException("Active revision has diverged"));
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
