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
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Spec 002 T100 — boundary IT for {@link xyz.tcheeric.cashu.mint.proto.tasks.BurnAmountValidator}
 * via {@code POST /v1/melt/bolt11}. Asserts SC-001:
 * under-funded melts are rejected with {@code insufficient_input} and
 * {@code LightningPaymentPort.pay} is never invoked.
 */
@Import(MeltBurnAmountIT.MockConfig.class)
class MeltBurnAmountIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    @org.springframework.beans.factory.annotation.Autowired
    LightningPaymentPort paymentPort;

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
        // Invoice is 100 sats with a 5-sat lightning reserve.
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
    }

    @Test
    void under_funded_melt_is_rejected_with_insufficient_input() {
        // Invoice=100, lightningReserve=5 → required = 105. Send 100 → fail.
        ResponseEntity<String> response = postMelt("quote-under",
                List.of(MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(32),
                        MeltProofFixture.proofJson(4)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("insufficient_input");
        // SC-001: no external payment was attempted.
        assertThat(((MockLightningPaymentPort) paymentPort).payInvocations()).isEmpty();
    }

    @Test
    void exactly_funded_melt_passes_validator() {
        // Invoice=100, reserve=5 → required = 105. Send 105 → must pass
        // the validator. Downstream the saga path will fail at
        // persistPendingProofs because this IT doesn't run the vault
        // service; what matters for SC-001 is that the response is NOT
        // `insufficient_input`.
        ResponseEntity<String> response = postMelt("quote-exact",
                List.of(MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(32),
                        MeltProofFixture.proofJson(8),
                        MeltProofFixture.proofJson(1)));

        assertThat(response.getBody())
                .as("validator pass → no insufficient_input code (status=%s)",
                        response.getStatusCode())
                .doesNotContain("insufficient_input");
    }

    @Test
    void over_funded_melt_passes_validator() {
        // Invoice=100, reserve=5 → required = 105. Send 200 → pass.
        ResponseEntity<String> response = postMelt("quote-over",
                List.of(MeltProofFixture.proofJson(128),
                        MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(8)));

        assertThat(response.getBody())
                .as("validator pass → no insufficient_input code (status=%s)",
                        response.getStatusCode())
                .doesNotContain("insufficient_input");
    }

    @Test
    void boundary_one_under_required_is_rejected() {
        // Invoice=100, reserve=5 → required = 105. Send 104 → fail.
        ResponseEntity<String> response = postMelt("quote-104",
                List.of(MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(32),
                        MeltProofFixture.proofJson(8)));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("insufficient_input");
        assertThat(((MockLightningPaymentPort) paymentPort).payInvocations()).isEmpty();
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
