package xyz.tcheeric.cashu.mint.observability.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the shipped Prometheus scrape config in step with the mint's management-port security.
 *
 * <p>{@code ManagementSecurityConfig} requires the operator credential for everything on the
 * management port except the health probes. A scrape job that does not present it is answered
 * with 401 on every scrape, which Prometheus records as {@code up == 0} and Grafana renders as
 * "No data" on every panel. That is exactly what emptied the staging dashboards: the security
 * chain landed and the scrape config was never told. Nothing else fails, so nothing else notices.
 */
class PrometheusScrapeConfigTest {

    private static final Path PROMETHEUS_CONFIG =
            Path.of("..", "cashu-mint-observability", "docker", "prometheus", "prometheus.yml");

    private static final String MINT_JOB = "cashu-mint";
    private static final String MANAGEMENT_METRICS_PATH = "/actuator/prometheus";

    private Map<String, Object> mintScrapeJob;

    /** Loads the {@code cashu-mint} scrape job from the config the observability stack ships. */
    @BeforeEach
    void loadMintScrapeJob() throws IOException {
        Map<String, Object> config = new Yaml().load(Files.readString(PROMETHEUS_CONFIG, StandardCharsets.UTF_8));
        mintScrapeJob = scrapeJobs(config).stream()
                .filter(job -> MINT_JOB.equals(job.get("job_name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("prometheus.yml declares no '" + MINT_JOB + "' job"));
    }

    // The mint job scrapes the authenticated actuator endpoint, so it must present credentials.
    @Test
    void mintScrapeJobPresentsTheOperatorCredential() {
        assertThat(mintScrapeJob.get("metrics_path")).isEqualTo(MANAGEMENT_METRICS_PATH);

        Map<String, Object> basicAuth = basicAuthOf(mintScrapeJob);
        assertThat(basicAuth)
                .as("the management port answers 401 without the operator credential, "
                        + "so a scrape job without basic_auth empties every dashboard")
                .isNotNull();
        assertThat(basicAuth.get("username")).isEqualTo("admin");
        assertThat(basicAuth.get("password_file"))
                .as("the password comes from a mounted secret, never from the tracked config")
                .isNotNull();
        assertThat(basicAuth).doesNotContainKey("password");
    }

    // Prometheus reads the password from a compose secret, so the path must be under /run/secrets.
    @Test
    void scrapePasswordIsReadFromAComposeSecret() {
        String passwordFile = String.valueOf(basicAuthOf(mintScrapeJob).get("password_file"));

        assertThat(passwordFile)
                .as("docker-compose.observability.yml mounts the credential as a compose secret")
                .startsWith("/run/secrets/");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scrapeJobs(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("scrape_configs");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> basicAuthOf(Map<String, Object> job) {
        return (Map<String, Object>) job.get("basic_auth");
    }
}
