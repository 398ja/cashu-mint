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
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 005 — fail-closed assertion for the melt-saga proof hold. When the
 * vault cannot durably bind every submitted proof to this saga, the mint
 * MUST NOT proceed to {@link LightningPaymentPort#pay}. The saga ends in
 * {@code FAILED} with a cached terminal {@code proofs_not_bound} error
 * and any partial hold is released.
 *
 * <p>This is the critical regression-cover IT for the
 * {@code backend-token-integrity-review-2026-05-24} highest-priority
 * finding: prior to spec 005, a partial bind logged a WARN and the saga
 * continued to {@code lightningPaymentPort.pay} with no durable proof
 * hold, leaving the JVM-crash window between pay-and-burn uncovered.
 *
 * <p>The proof-vault is mocked so the test can deterministically force
 * {@code insertOrClaimForSaga} into a partial / zero / exception
 * outcome. The vault-side atomic primitive is covered separately in
 * {@code ProofVaultControllerIntegrationTest.InsertOrClaimTests}
 * (cashu-vault repo).
 */
@Import(MeltSagaProofsNotBoundIT.MockConfig.class)
class MeltSagaProofsNotBoundIT extends AbstractMintDurableIT {

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
        // Provide a managed mint entity so MeltTask.buildNormalisedProofEntities
        // can Y-normalise without NPEing on the mocked vault service.
        xyz.tcheeric.cashu.vault.db.model.MintEntity me =
                new xyz.tcheeric.cashu.vault.db.model.MintEntity();
        me.setId(UUID.fromString(mint.getId()));
        when(mintVaultService.retrieveMint(anyString())).thenReturn(me);
        transitions.deleteAll();
        sagas.deleteAll();
        ((MockLightningPaymentPort) paymentPort).reset();
    }

    @Test
    void partial_bind_fails_closed_before_payment_T501() throws Exception {
        // Two proofs submitted, only one durably bound — the other slipped
        // away (concurrent saga holds it / already spent / SPENT lookup
        // raced). The mint MUST NOT call lightningPaymentPort.pay.
        when(proofVaultService.insertOrClaimForSaga(any(), anyString(), any(UUID.class)))
                .thenReturn(1);

        ResponseEntity<String> response = postMelt("quote-partial", overFundedProofs());

        // The wallet receives an error response carrying proofs_not_bound.
        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getBody()).contains("proofs_not_bound");

        // Saga lands in FAILED.
        MeltSagaEntity saga = sagas.findByQuoteId("quote-partial").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.FAILED);

        // Transition timeline: PROOFS_HELD → FAILED (no PAYMENT_SENT).
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .containsExactly(MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);

        // CRITICAL: lightningPaymentPort.pay was never invoked.
        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-partial"))
                .as("partial bind must abort BEFORE external payment")
                .isZero();

        // Partial hold released for this saga.
        verify(proofVaultService).refundForSaga(saga.getMeltSagaId());
    }

    @Test
    void zero_bind_fails_closed_before_payment_T502() throws Exception {
        // Most common failure shape: every submitted proof is already
        // held by another saga / already spent. insert-or-claim returns 0.
        when(proofVaultService.insertOrClaimForSaga(any(), anyString(), any(UUID.class)))
                .thenReturn(0);

        ResponseEntity<String> response = postMelt("quote-zero", overFundedProofs());

        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getBody()).contains("proofs_not_bound");

        MeltSagaEntity saga = sagas.findByQuoteId("quote-zero").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.FAILED);
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .containsExactly(MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);

        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-zero")).isZero();
    }

    @Test
    void vault_exception_during_bind_fails_closed_before_payment_T503() throws Exception {
        // Vault unreachable during insert-or-claim — must not proceed
        // to external payment. The client sees the spec-005 terminal
        // error proofs_not_bound (NOT the underlying vault exception);
        // any partial hold is released.
        when(proofVaultService.insertOrClaimForSaga(any(), anyString(), any(UUID.class)))
                .thenThrow(new RuntimeException("vault_unreachable_for_bind"));

        ResponseEntity<String> response = postMelt("quote-vault-down", overFundedProofs());

        assertThat(response.getStatusCode().isError()).isTrue();
        // Client-facing terminal error is the same proofs_not_bound code as
        // the partial / zero-bind cases above — never leaks the vault cause.
        assertThat(response.getBody()).contains("proofs_not_bound");

        // The saga record exists, and lightningPaymentPort.pay was never invoked.
        MeltSagaEntity saga = sagas.findByQuoteId("quote-vault-down").orElseThrow();
        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-vault-down"))
                .as("vault unreachable during bind must NOT trigger external payment")
                .isZero();
        // Refund was attempted to clear any partial hold for this saga.
        verify(proofVaultService).refundForSaga(saga.getMeltSagaId());
    }

    @Test
    void happy_path_proves_bind_happens_before_pay_T504() throws Exception {
        // Spec 005 / SC-003 — explicit interaction-ordering proof: the
        // saga's insertOrClaimForSaga call MUST run before
        // lightningPaymentPort.pay. The cashu-vault IT
        // (ProofVaultControllerIntegrationTest.InsertOrClaimTests)
        // covers the durable side; this IT covers the call ordering
        // through the live REST + saga ledger.
        when(proofVaultService.insertOrClaimForSaga(any(), anyString(), any(UUID.class)))
                .thenAnswer(inv -> {
                    List<?> rows = inv.getArgument(0);
                    return rows.size();
                });
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-happy-005", 100L, 0L, "evt-happy-005"));

        ResponseEntity<String> response = postMelt("quote-happy-005", overFundedProofs());

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();

        MeltSagaEntity saga = sagas.findByQuoteId("quote-happy-005").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.COMPLETED);

        // Bind ran exactly once.
        verify(proofVaultService).insertOrClaimForSaga(any(), anyString(), any(UUID.class));
        // Pay ran exactly once.
        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-happy-005"))
                .isEqualTo(1);
        // Refund was NOT called on the happy path.
        verify(proofVaultService, never()).refundForSaga(anyString());

        // Bind-before-pay ordering is enforced by the saga ledger:
        // PROOFS_HELD (seq=1) precedes PAYMENT_SENT (seq=2). The vault
        // insert-or-claim that backs PROOFS_HELD therefore necessarily
        // ran before lightningPaymentPort.pay was invoked.
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline.get(0).getToState()).isEqualTo(MeltSagaState.PROOFS_HELD);
        int proofsHeldSeq = timeline.stream()
                .filter(t -> t.getToState() == MeltSagaState.PROOFS_HELD)
                .findFirst().orElseThrow().getSeq();
        int paymentSentSeq = timeline.stream()
                .filter(t -> t.getToState() == MeltSagaState.PAYMENT_SENT)
                .findFirst().orElseThrow().getSeq();
        assertThat(proofsHeldSeq).isLessThan(paymentSentSeq);
    }

    private static List<Map<String, Object>> overFundedProofs() {
        return List.of(MeltProofFixture.proofJson(64), MeltProofFixture.proofJson(64));
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
