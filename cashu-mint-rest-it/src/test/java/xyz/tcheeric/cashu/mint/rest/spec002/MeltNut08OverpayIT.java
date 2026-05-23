package xyz.tcheeric.cashu.mint.rest.spec002;

import com.fasterxml.jackson.databind.JsonNode;
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
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
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
 * Spec 002 T205 — NUT-08 overpaid-melt change return IT. Drives
 * {@code POST /v1/melt/bolt11} with proofs summing to more than
 * {@code invoice + exactFeeReserve} AND a list of {@code outputs}
 * blinded messages. Asserts:
 *
 * <ul>
 *   <li>Saga reaches {@code COMPLETED}.</li>
 *   <li>Response {@code change} carries one signed {@code BlindSignature}
 *       per requested output.</li>
 *   <li>Saga {@code melt_response_cache} is populated (T216
 *       cached-response replay).</li>
 *   <li>Overpay-with-no-outputs path still succeeds; {@code change}
 *       field is absent / null (excess is forfeited).</li>
 * </ul>
 */
@Import(MeltNut08OverpayIT.MockConfig.class)
class MeltNut08OverpayIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    @MockBean
    ProofVaultService proofVaultService;

    @MockBean
    MintVaultService mintVaultService;

    @org.springframework.beans.factory.annotation.Autowired
    LightningPaymentPort paymentPort;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaJpaRepository sagas;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaTransitionJpaRepository transitions;

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
        // Invoice = 100 sat, gateway-reserve = 5 sat. Total required: 105.
        when(gateway.getAmount(anyString())).thenReturn(100);
        when(gateway.getRequest(anyString())).thenReturn("lnbc100...");
        when(gateway.getFeeReserve(anyString())).thenReturn(5);

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
        Mockito.doAnswer(inv -> MeltProofFixture.privateKeyFor(inv.getArgument(1, Integer.class)))
                .when(stub).getPrivateKey(anyString(), org.mockito.ArgumentMatchers.anyInt(), any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void setup() throws Exception {
        Mint mint = MeltProofFixture.mintWithKeys();
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        when(mintLoadService.keySet(anyString())).thenReturn(mint.getKeySets().iterator().next());
        when(mintLoadService.keySets()).thenReturn(List.copyOf(mint.getKeySets()));
        xyz.tcheeric.cashu.vault.db.model.MintEntity me =
                new xyz.tcheeric.cashu.vault.db.model.MintEntity();
        me.setId(UUID.fromString(mint.getId()));
        when(mintVaultService.retrieveMint(anyString())).thenReturn(me);
        transitions.deleteAll();
        sagas.deleteAll();
        ((MockLightningPaymentPort) paymentPort).reset();
    }

    @Test
    void overpay_with_outputs_returns_signed_change_T205() throws Exception {
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-overpay", 100L, 0L, "evt-overpay"));

        // proofSum = 128 + 16 + 8 = 152.  Overpaid = 152 - 100 - 5 = 47.
        // Outputs sum 32 + 8 + 4 + 2 + 1 = 47.
        List<Map<String, Object>> proofs = List.of(
                MeltProofFixture.proofJson(128),
                MeltProofFixture.proofJson(16),
                MeltProofFixture.proofJson(8));
        List<Map<String, Object>> outputs = List.of(
                blindedMessageJson(32, 1),
                blindedMessageJson(8, 2),
                blindedMessageJson(4, 3),
                blindedMessageJson(2, 4),
                blindedMessageJson(1, 5));

        ResponseEntity<String> response = postMeltWithOutputs("quote-overpay", proofs, outputs);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("paid").asBoolean()).isTrue();
        JsonNode change = body.get("change");
        assertThat(change).as("response must carry NUT-08 change array").isNotNull();
        assertThat(change.isArray()).isTrue();
        assertThat(change.size()).isEqualTo(5);
        // Each change entry MUST carry amount + id + C_ (signed point).
        for (JsonNode sig : change) {
            assertThat(sig.has("amount")).isTrue();
            assertThat(sig.has("id")).isTrue();
            assertThat(sig.has("C_")).isTrue();
        }

        MeltSagaEntity saga = sagas.findByQuoteId("quote-overpay").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.COMPLETED);
        // T216 — response cache populated with the same change payload.
        assertThat(saga.getMeltResponseCache())
                .as("melt_response_cache MUST persist the signed change for NUT-19 replay")
                .contains("\"change\"");
    }

    @Test
    void overpay_without_outputs_completes_without_change_T205() throws Exception {
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-no-change", 100L, 0L, "evt-no-change"));

        // Same overpaid proofs as above, but no outputs provided. The mint
        // MUST complete the melt; the overpaid amount is forfeited.
        List<Map<String, Object>> proofs = List.of(
                MeltProofFixture.proofJson(128),
                MeltProofFixture.proofJson(16),
                MeltProofFixture.proofJson(8));

        ResponseEntity<String> response = postMeltWithOutputs("quote-overpay-no-outputs",
                proofs, /*outputs*/ List.of());

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("paid").asBoolean()).isTrue();
        // change is absent (NON_NULL JsonInclude on the field) or null.
        assertThat(body.get("change") == null || body.get("change").isNull())
                .as("no outputs supplied → no change field in response")
                .isTrue();
        MeltSagaEntity saga = sagas.findByQuoteId("quote-overpay-no-outputs").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.COMPLETED);
    }

    @Test
    void outputs_exceeding_overpaid_amount_are_skipped_T205() throws Exception {
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-too-many", 100L, 0L, "evt-too-many"));

        // Overpaid = 47. Wallet attempts outputs summing to 48 → mint skips
        // NUT-08 entirely (no over-signing). Melt still completes.
        List<Map<String, Object>> proofs = List.of(
                MeltProofFixture.proofJson(128),
                MeltProofFixture.proofJson(16),
                MeltProofFixture.proofJson(8));
        List<Map<String, Object>> outputs = List.of(
                blindedMessageJson(32, 1),
                blindedMessageJson(16, 2)); // 48 > 47

        ResponseEntity<String> response = postMeltWithOutputs("quote-too-many",
                proofs, outputs);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("melt still completes; status=%s body=%s",
                        response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("change") == null || body.get("change").isNull())
                .as("outputs exceeding overpayment MUST NOT be signed")
                .isTrue();
        MeltSagaEntity saga = sagas.findByQuoteId("quote-too-many").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.COMPLETED);
    }

    private Map<String, Object> blindedMessageJson(int amount, int salt) {
        xyz.tcheeric.cashu.common.BlindedMessage bm =
                MeltProofFixture.blindedMessageForChange(amount, salt);
        return Map.of(
                "amount", amount,
                "id", MeltProofFixture.KEYSET_ID,
                "B_", bm.getBlindedMessage().toString());
    }

    private ResponseEntity<String> postMeltWithOutputs(String quoteId,
                                                       List<Map<String, Object>> proofs,
                                                       List<Map<String, Object>> outputs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "quote", quoteId,
                    "inputs", proofs,
                    "outputs", outputs));
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
