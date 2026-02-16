package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class CliToRestE2EIT extends AbstractAdminE2EIT {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Verifies CLI lifecycle commands can target the real admin REST API and return JSON output.
    @Test
    void shouldExecuteCliLifecycleCommandAgainstRealRestApi() throws Exception {
        final String mintId = UUID.randomUUID().toString();

        final CliResult createResult = runCli(
            "--api-url", adminApiBaseUrl(),
            "--api-key", ADMIN_TOKEN,
            "create",
            "--mint-id", mintId,
            "--operator-id", OPERATOR_ID,
            "--version-tag", "cli-v1",
            "--yes",
            "--output-format", "JSON");

        assertThat(createResult.exitCode()).isEqualTo(0);

        final JsonNode jsonOutput = OBJECT_MAPPER.readTree(createResult.output());
        assertThat(jsonOutput.path("mintId").asText()).isEqualTo(mintId);
        assertThat(jsonOutput.path("operation").asText()).isEqualTo("CREATE");
        assertThat(jsonOutput.path("currentState").asText()).isEqualTo("PROVISIONED");
    }

    // Verifies CLI dry-run mode does not mutate server state before an actual create invocation.
    @Test
    void shouldNotMutateStateWhenCliDryRunIsUsed() throws Exception {
        final String mintId = UUID.randomUUID().toString();

        final CliResult dryRunResult = runCli(
            "--api-url", adminApiBaseUrl(),
            "--api-key", ADMIN_TOKEN,
            "create",
            "--mint-id", mintId,
            "--operator-id", OPERATOR_ID,
            "--version-tag", "cli-dry-run",
            "--dry-run");

        assertThat(dryRunResult.exitCode()).isEqualTo(0);
        assertThat(dryRunResult.output()).contains("[DRY RUN]");
        assertThat(dryRunResult.output()).contains("No changes will be made.");

        final CliResult createResult = runCli(
            "--api-url", adminApiBaseUrl(),
            "--api-key", ADMIN_TOKEN,
            "create",
            "--mint-id", mintId,
            "--operator-id", OPERATOR_ID,
            "--version-tag", "cli-dry-run",
            "--yes",
            "--output-format", "JSON");

        assertThat(createResult.exitCode()).isEqualTo(0);
        final JsonNode jsonOutput = OBJECT_MAPPER.readTree(createResult.output());
        assertThat(jsonOutput.path("mintId").asText()).isEqualTo(mintId);
    }

    private CliResult runCli(final String... args) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("xyz.tcheeric.cashu.mint.admin.cli.MintAdminCliApplication");
        command.addAll(List.of(args));

        final Process process = new ProcessBuilder(command)
            .start();

        final boolean finished = process.waitFor(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("CLI command timed out: " + String.join(" ", command));
        }

        final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new CliResult(process.exitValue(), stdout, stderr);
    }

    private record CliResult(int exitCode, String output, String errorOutput) {
    }
}
