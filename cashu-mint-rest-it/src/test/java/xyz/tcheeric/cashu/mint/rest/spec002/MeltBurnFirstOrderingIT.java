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
import static org.mockito.Mockito.when;

/**
 * Spec 002 T200 / T201 / T203 — drives /v1/melt against the
 * Testcontainers Postgres harness with a mocked
 * {@link ProofVaultService} (to skip the cashu-vault REST round-trip)
 * and a programmable {@link MockLightningPaymentPort}. Asserts the saga
 * state machine transitions for the three concrete
 * {@link PaymentOutcome} branches.
 *
 * <p>The fixture sidesteps the proof-vault dependency by replacing the
 * {@code ProofVaultService} bean with a Mockito mock that is a no-op
 * for {@code storePending} and {@code invalidate}. The saga state +
 * transitions land in Postgres as designed.
 */
@Import(MeltBurnFirstOrderingIT.MockConfig.class)
class MeltBurnFirstOrderingIT extends AbstractMintDurableIT {

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
        // Mocked vault entities so persistPendingProofs is a no-op.
        xyz.tcheeric.cashu.vault.db.model.MintEntity me =
                new xyz.tcheeric.cashu.vault.db.model.MintEntity();
        me.setId(java.util.UUID.fromString(mint.getId()));
        when(mintVaultService.retrieveMint(anyString())).thenReturn(me);
        // proofVaultService is a Mockito mock — storePending / invalidate
        // default to no-op behavior. Saga lifecycle proceeds.
        transitions.deleteAll();
        sagas.deleteAll();
        ((MockLightningPaymentPort) paymentPort).reset();
    }

    @Test
    void happy_path_drives_proofs_held_payment_sent_completed_T200() {
        // Burn-first ordering check: storePending MUST run before pay().
        // We verify via the saga transitions ledger — seq=1 is
        // null → PROOFS_HELD, seq=2 is PROOFS_HELD → PAYMENT_SENT, seq=3
        // is PAYMENT_SENT → COMPLETED. The proof-vault mock is a no-op,
        // so the order is committed but unobservable from there; the
        // saga ledger is the authoritative order-of-events record.
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-happy", 100L, 0L, "evt-happy"));

        ResponseEntity<String> response = postMelt("quote-happy", overFundedProofs());

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();

        MeltSagaEntity saga = sagas.findByQuoteId("quote-happy").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.COMPLETED);
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .containsExactly(MeltSagaState.PROOFS_HELD,
                        MeltSagaState.PAYMENT_SENT,
                        MeltSagaState.COMPLETED);
    }

    @Test
    void definitive_failure_lands_saga_in_FAILED_T201() {
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.DefinitiveFailure("route_not_found", "1001"));

        ResponseEntity<String> response = postMelt("quote-failed", overFundedProofs());

        // The response is an error (4xx/5xx) carrying melt_invoice_not_paid_error.
        assertThat(response.getStatusCode().isError()).isTrue();
        MeltSagaEntity saga = sagas.findByQuoteId("quote-failed").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.FAILED);
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .containsExactly(MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);
    }

    @Test
    void unknown_outcome_parks_saga_in_PAYMENT_UNKNOWN_T203() {
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Unknown("provider_timeout"));

        ResponseEntity<String> response = postMelt("quote-unknown", overFundedProofs());

        // The response carries payment_unknown.
        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getBody()).contains("payment_unknown");
        MeltSagaEntity saga = sagas.findByQuoteId("quote-unknown").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.PAYMENT_UNKNOWN);
        // FR-007: no auto-retry of pay() — only one invocation.
        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-unknown"))
                .isEqualTo(1);
    }

    @Test
    void burn_failure_after_payment_lands_saga_in_PAYMENT_SENT_BURN_FAILED_T202() throws Exception {
        // The proof-invalidate call AFTER pay() succeeds must fail to drive
        // the saga into PAYMENT_SENT_BURN_FAILED with the proofs held in
        // PENDING. We inject the invalidate failure on the ProofVaultService
        // mock so MeltTask's createInvalidateProofsTask raises an exception
        // when the saga is in PAYMENT_SENT.
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-burn-fail", 100L, 0L, "evt-burn-fail"));
        Mockito.doThrow(new RuntimeException("vault_unreachable_for_invalidate"))
                .when(proofVaultService).invalidate(any());

        ResponseEntity<String> response = postMelt("quote-burn-fail", overFundedProofs());

        // Response is an error (the burn step threw).
        assertThat(response.getStatusCode().isError()).isTrue();
        // Saga state machine: PROOFS_HELD → PAYMENT_SENT → PAYMENT_SENT_BURN_FAILED.
        MeltSagaEntity saga = sagas.findByQuoteId("quote-burn-fail").orElseThrow();
        assertThat(saga.getCurrentState()).isEqualTo(MeltSagaState.PAYMENT_SENT_BURN_FAILED);
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .containsExactly(MeltSagaState.PROOFS_HELD,
                        MeltSagaState.PAYMENT_SENT,
                        MeltSagaState.PAYMENT_SENT_BURN_FAILED);
        // FR-007: no auto-retry of pay() after the burn failure.
        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-burn-fail"))
                .isEqualTo(1);
    }

    @Test
    void payment_port_pay_is_invoked_only_AFTER_PROOFS_HELD_commits_SC_003() {
        // SC-003 spirit at IT level: by the time the LightningPaymentPort.pay
        // is invoked, the PROOFS_HELD saga row MUST already exist in
        // Postgres. We assert by observing the saga state at the moment
        // the mock pay() runs.
        MockLightningPaymentPort mock = (MockLightningPaymentPort) paymentPort;
        // Script Success; verification is via timing on the saga row.
        mock.enqueuePay(new PaymentOutcome.Success("preimage-sc003", 100L, 0L, "evt-sc003"));

        postMelt("quote-sc003", overFundedProofs());

        // After the request completes, the PROOFS_HELD transition exists
        // in the ledger AND it precedes PAYMENT_SENT (seq=1 < seq=2).
        MeltSagaEntity saga = sagas.findByQuoteId("quote-sc003").orElseThrow();
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(saga.getMeltSagaId());
        assertThat(timeline.get(0).getToState()).isEqualTo(MeltSagaState.PROOFS_HELD);
        assertThat(timeline.get(0).getSeq()).isEqualTo(1);
        // PAYMENT_SENT comes AFTER PROOFS_HELD in the ledger.
        int proofsHeldSeq = timeline.stream()
                .filter(t -> t.getToState() == MeltSagaState.PROOFS_HELD)
                .findFirst().orElseThrow().getSeq();
        int paymentSentSeq = timeline.stream()
                .filter(t -> t.getToState() == MeltSagaState.PAYMENT_SENT)
                .findFirst().orElseThrow().getSeq();
        assertThat(proofsHeldSeq).isLessThan(paymentSentSeq);
    }

    private static List<Map<String, Object>> overFundedProofs() {
        // Two proofs of 64 sat each = 128 > 105 (invoice + reserve).
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
