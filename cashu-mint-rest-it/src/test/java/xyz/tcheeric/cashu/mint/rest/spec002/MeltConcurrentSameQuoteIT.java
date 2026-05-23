package xyz.tcheeric.cashu.mint.rest.spec002;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MeltProofFixture;
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
 * Spec 002 T204 — concurrent melt requests for the same {@code quote_id}.
 * The first request creates a non-terminal saga (PROOFS_HELD); the second
 * request MUST be rejected with {@code melt_in_progress} (FR-005).
 *
 * <p>To avoid the IT requirement on a working vault for
 * {@code persistPendingProofs}, this test pre-seeds a non-terminal saga
 * directly via the JPA repository and then drives a single melt request
 * with the same quote id, asserting it bounces off the existing-saga
 * guard at the top of {@code MeltTask.executeWithSaga}. The "two
 * simultaneous requests" semantics is covered by the saga's unique
 * constraint on {@code quote_id} and the early {@code findByQuoteId}
 * check.
 */
class MeltConcurrentSameQuoteIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaJpaRepository sagas;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaTransitionJpaRepository transitions;

    private final RestTemplate restTemplate = new RestTemplate();

    private static MintProtocolService originalProtocolService;

    @BeforeAll
    static void overrideProtocolService() throws Exception {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getName()).thenReturn("mock-gateway");
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
    void wireMockMintLoaderAndCleanSagas() throws Exception {
        Mint mint = MeltProofFixture.mintWithKeys();
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        when(mintLoadService.keySet(anyString())).thenReturn(mint.getKeySets().iterator().next());
        when(mintLoadService.keySets()).thenReturn(List.copyOf(mint.getKeySets()));
        transitions.deleteAll();
        sagas.deleteAll();
    }

    @Test
    void second_melt_for_same_quote_rejected_with_melt_in_progress_FR_005() {
        // Pre-seed a non-terminal saga for quote-concurrent.
        MeltSagaEntity seeded = new MeltSagaEntity();
        seeded.setMeltSagaId(UUID.randomUUID().toString());
        seeded.setQuoteId("quote-concurrent");
        seeded.setInvoiceAmount(100L);
        seeded.setExactFeeReserve(5L);
        seeded.setInputAmount(105L);
        seeded.setProofCount(2);
        seeded.setProvider("mock-gateway");
        seeded.setCurrentState(MeltSagaState.PROOFS_HELD);
        sagas.save(seeded);

        // Send a /v1/melt for the same quote with valid (over-funded) proofs.
        ResponseEntity<String> response = postMelt("quote-concurrent",
                List.of(MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(64)));

        assertThat(response.getStatusCode().isError())
                .as("FR-005 melt_in_progress on concurrent quote (status=%s body=%s)",
                        response.getStatusCode(), response.getBody())
                .isTrue();
        assertThat(response.getBody()).contains("melt_in_progress");
        // The pre-seeded saga is still the only one for that quote_id.
        assertThat(sagas.count()).isEqualTo(1);
    }

    @Test
    void terminal_saga_does_NOT_trigger_melt_in_progress() {
        // Terminal sagas (COMPLETED) should not block a new attempt at the
        // melt_in_progress check — they fail later for OTHER reasons (the
        // melt_saga.quote_id unique constraint), but the early check
        // doesn't bail out with `melt_in_progress`.
        MeltSagaEntity seeded = new MeltSagaEntity();
        seeded.setMeltSagaId(UUID.randomUUID().toString());
        seeded.setQuoteId("quote-terminal");
        seeded.setInvoiceAmount(100L);
        seeded.setExactFeeReserve(5L);
        seeded.setInputAmount(105L);
        seeded.setProofCount(2);
        seeded.setProvider("mock-gateway");
        seeded.setCurrentState(MeltSagaState.COMPLETED);
        sagas.save(seeded);

        ResponseEntity<String> response = postMelt("quote-terminal",
                List.of(MeltProofFixture.proofJson(64),
                        MeltProofFixture.proofJson(64)));

        assertThat(response.getBody())
                .as("status=%s — terminal saga shouldn't trip melt_in_progress",
                        response.getStatusCode())
                .doesNotContain("melt_in_progress");
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
