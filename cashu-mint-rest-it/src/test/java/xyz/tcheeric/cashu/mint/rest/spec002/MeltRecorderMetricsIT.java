package xyz.tcheeric.cashu.mint.rest.spec002;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.mint.proto.metrics.MeltMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MeltProofFixture;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MockLightningPaymentPort;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Issue #340 — end-to-end proof of the typed recorder seam (ADR 0001) on the
 * melt area.
 *
 * <p>Asserting on a {@code SimpleMeterRegistry} would only prove that the
 * recorder calls Micrometer. What broke before was the other end: metrics that
 * existed in code but never reached a scrape, and names on dashboards that no
 * code emitted. So this IT drives a real melt through
 * {@code POST /v1/melt/bolt11}, then reads the Prometheus exposition off the
 * management port and asserts the family, the metric name, its labels and its
 * value there.
 *
 * <p>{@code CASHU_MINT_MANAGEMENT_PORT=0} drives the placeholder in
 * {@code application.properties} rather than {@code management.server.port}
 * directly, so the production wiring stays under test (see
 * {@code ActuatorManagementPortIT}). The {@code test} profile disables
 * observability; this IT needs it on, or the scrape would carry no mint series
 * and the assertions would fail for the wrong reason.
 */
@Import(MeltRecorderMetricsIT.MockConfig.class)
@TestPropertySource(properties = {
        "CASHU_MINT_MANAGEMENT_PORT=0",
        "cashu.observability.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics"
})
class MeltRecorderMetricsIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INSUFFICIENT_INPUT = "cashu_mint_melt_insufficient_input_total";

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @MockBean
    MintLoadService mintLoadService;

    @org.springframework.beans.factory.annotation.Autowired
    LightningPaymentPort paymentPort;

    /**
     * {@code MetricRecorders} is a JVM-global, last-writer-wins static and every
     * observability-enabled context registers into it on refresh. Spring's
     * test-context cache keeps those contexts alive, so whichever refreshed last
     * owns the global — {@code ActuatorManagementPortIT} also enables
     * observability, and if its context refreshes after this one, the melt POSTs
     * below would increment counters in <em>its</em> registry while we scrape
     * ours. Re-registering this context's bean before each test pins the global
     * to the registry we assert against, whatever the class order turns out
     * to be.
     */
    @org.springframework.beans.factory.annotation.Autowired
    MeltMetricsRecorder meltMetricsRecorder;

    private final RestTemplate restTemplate = new RestTemplate();

    @TestConfiguration
    static class MockConfig {
        @Bean
        @Primary
        public LightningPaymentPort mockLightningPaymentPort() {
            return new MockLightningPaymentPort();
        }
    }

    private static MintProtocolService originalProtocolService;

    @BeforeAll
    static void overrideProtocolService() throws Exception {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getName()).thenReturn("mock-gateway");
        // Invoice is 100 sats with a 5-sat lightning reserve → required = 105.
        when(gateway.getAmount(anyString())).thenReturn(100);
        when(gateway.getRequest(anyString())).thenReturn("lnbc100...");
        when(gateway.getFeeReserve(anyString())).thenReturn(5);

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
        // BDHKE verify uses the matching small-hex private key.
        Mockito.doAnswer(inv -> MeltProofFixture.privateKeyFor(inv.getArgument(1, Integer.class)))
                .when(stub).getPrivateKey(anyString(), org.mockito.ArgumentMatchers.anyInt(), any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void wireMockMintLoader() throws Exception {
        Mint mint = MeltProofFixture.mintWithKeys();
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        when(mintLoadService.keySet(anyString())).thenReturn(mint.getKeySets().iterator().next());
        when(mintLoadService.keySets()).thenReturn(List.copyOf(mint.getKeySets()));
        ((MockLightningPaymentPort) paymentPort).reset();
        MetricRecorders.registerMelt(meltMetricsRecorder);
    }

    /**
     * The melt rejection recorded through {@code MeltMetricsRecorder} must show
     * up in the scrape, under the mandated name, with the common labels, at the
     * exact count of rejections driven.
     */
    @Test
    void underFundedMeltsAreCountedInThePrometheusScrape() {
        double before = counterValue(scrape(), INSUFFICIENT_INPUT);

        postUnderFundedMelt("quote-metrics-1");
        postUnderFundedMelt("quote-metrics-2");

        String scrape = scrape();

        assertThat(scrape)
                .as("the melt family must be declared in the exposition")
                .containsPattern("# TYPE cashu_mint_melt_insufficient_input(_total)? counter");
        assertThat(labelsOf(scrape, INSUFFICIENT_INPUT))
                .as("common tags from management.metrics.tags.* must be on the series")
                .contains("application=\"cashu-mint\"")
                .contains("env=");
        assertThat(counterValue(scrape, INSUFFICIENT_INPUT) - before)
                .as("two under-funded melts → two increments")
                .isEqualTo(2.0);
    }

    /**
     * A metric declared on the recorder must be scrapeable before it first
     * fires — a family that only appears on failure is indistinguishable from a
     * broken one on a dashboard.
     */
    @Test
    void proofsNotBoundCounterIsRegisteredEvenWhenNeverIncremented() {
        assertThat(scrape())
                .containsPattern("# TYPE cashu_mint_melt_proofs_not_bound(_total)? counter");
    }

    private void postUnderFundedMelt(String quoteId) {
        // Required = 105. Send 100 → insufficient_input, no payment attempted.
        ResponseEntity<String> response = postMelt(quoteId,
                List.of(MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(32),
                        MeltProofFixture.proofJson(4)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("insufficient_input");
    }

    private String scrape() {
        return restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class).getBody();
    }

    /** The label block of the single series for {@code metric}, or "" when absent. */
    private String labelsOf(String scrape, String metric) {
        Matcher matcher = sampleMatcher(scrape, metric);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** The value of the single series for {@code metric}, or 0 when not yet present. */
    private double counterValue(String scrape, String metric) {
        Matcher matcher = sampleMatcher(scrape, metric);
        return matcher.find() ? Double.parseDouble(matcher.group(2)) : 0.0;
    }

    private Matcher sampleMatcher(String scrape, String metric) {
        return Pattern.compile("^" + Pattern.quote(metric) + "\\{([^}]*)}\\s+([0-9.E+-]+)$",
                Pattern.MULTILINE).matcher(scrape == null ? "" : scrape);
    }

    private ResponseEntity<String> postMelt(String quoteId, List<Map<String, Object>> proofs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "quote", quoteId,
                    "inputs", proofs));
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            return restTemplate.postForEntity(
                    "http://localhost:" + port + "/v1/melt/bolt11", entity, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(new String(e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
