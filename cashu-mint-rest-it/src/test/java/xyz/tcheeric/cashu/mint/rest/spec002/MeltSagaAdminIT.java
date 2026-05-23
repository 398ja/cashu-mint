package xyz.tcheeric.cashu.mint.rest.spec002;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.beans.factory.annotation.Autowired.*;

/**
 * Spec 002 T300-T302 — drives {@code GET /admin/melt-saga/by-id/{id}} +
 * {@code by-quote/{quoteId}} + {@code POST /{id}/mark-resolved} against
 * the Testcontainers Postgres harness, and asserts the response shape +
 * the append-only mark-resolved contract.
 */
class MeltSagaAdminIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Value("${local.server.port}")
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaJpaRepository meltSagaRepository;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaTransitionJpaRepository meltSagaTransitionRepository;

    private static final String ADMIN_USER = "admin-it";
    private static final String ADMIN_PASS = "it-admin-password";

    private final RestTemplate restTemplate = new RestTemplate();

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(ADMIN_USER, ADMIN_PASS, StandardCharsets.UTF_8);
        return headers;
    }

    private ResponseEntity<String> getAdmin(String url) {
        try {
            return restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<String>(null, adminHeaders()), String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
    }

    @BeforeEach
    void cleanMeltSaga() {
        meltSagaTransitionRepository.deleteAll();
        meltSagaRepository.deleteAll();
    }

    @Test
    void byId_returns_saga_with_full_transition_timeline() throws Exception {
        seed("saga-q-1", "quote-q-1", MeltSagaState.PAYMENT_UNKNOWN);

        ResponseEntity<String> response = getAdmin(
                "http://localhost:" + port + "/admin/melt-saga/by-id/saga-q-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("meltSagaId").asText()).isEqualTo("saga-q-1");
        assertThat(body.get("quoteId").asText()).isEqualTo("quote-q-1");
        assertThat(body.get("currentState").asText()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(body.get("invoiceAmount").asLong()).isEqualTo(100L);
        assertThat(body.get("exactFeeReserve").asLong()).isEqualTo(5L);
        assertThat(body.get("transitions")).hasSize(2); // PROOFS_HELD + PAYMENT_UNKNOWN seeded below
        assertThat(body.get("transitions").get(0).get("toState").asText()).isEqualTo("PROOFS_HELD");
        assertThat(body.get("transitions").get(1).get("toState").asText()).isEqualTo("PAYMENT_UNKNOWN");
    }

    @Test
    void byQuote_returns_the_same_saga() throws Exception {
        seed("saga-q-2", "quote-q-2", MeltSagaState.COMPLETED);

        ResponseEntity<String> response = getAdmin(
                "http://localhost:" + port + "/admin/melt-saga/by-quote/quote-q-2");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("meltSagaId").asText()).isEqualTo("saga-q-2");
        assertThat(body.get("currentState").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void byId_response_pins_the_documented_json_shape_T302() throws Exception {
        seed("saga-shape", "quote-shape", MeltSagaState.COMPLETED);

        ResponseEntity<String> response = getAdmin(
                "http://localhost:" + port + "/admin/melt-saga/by-id/saga-shape");

        JsonNode body = MAPPER.readTree(response.getBody());
        // Spec 002 T302 — pin the public field set so a future rename /
        // removal trips this contract test. The set MUST match the
        // README admin-endpoint documentation in cashu-mint-rest/README.md.
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "meltSagaId",
                "quoteId",
                "currentState",
                "invoiceAmount",
                "exactFeeReserve",
                "inputAmount",
                "proofCount",
                "provider",
                "createdAt",
                "updatedAt",
                "transitions");
        JsonNode t = body.get("transitions").get(0);
        assertThat(t.fieldNames()).toIterable().contains("seq", "toState", "actor", "at");
    }

    @Test
    void markResolved_records_actor_and_reason_as_operator_action_T301() throws Exception {
        // Spec 002 T301 — explicit OperatorReconciliationIT-style assertion
        // sitting on top of the markResolved test. From a PAYMENT_SENT_BURN_FAILED
        // saga, the operator endpoint appends an operator: actor entry
        // (append-only contract; never overwrites current_state).
        seed("saga-t301", "quote-t301", MeltSagaState.PAYMENT_SENT_BURN_FAILED);

        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                MAPPER.writeValueAsString(Map.of(
                        "actor", "operator:carol",
                        "reason", "T301 — manually reconciled")),
                headers);
        restTemplate.exchange(
                "http://localhost:" + port + "/admin/melt-saga/saga-t301/mark-resolved",
                HttpMethod.POST, entity, String.class);

        List<MeltSagaTransitionEntity> timeline =
                meltSagaTransitionRepository.findTimeline("saga-t301");
        // The mark-resolved entry MUST be the latest in the timeline; older
        // entries are untouched.
        MeltSagaTransitionEntity last = timeline.get(timeline.size() - 1);
        assertThat(last.getActor()).isEqualTo("operator:carol");
        assertThat(last.getReason()).isEqualTo("T301 — manually reconciled");
        // current_state has NOT changed.
        assertThat(meltSagaRepository.findById("saga-t301").orElseThrow()
                .getCurrentState()).isEqualTo(MeltSagaState.PAYMENT_SENT_BURN_FAILED);
    }

    @Test
    void byId_unknown_returns_404() {
        ResponseEntity<String> response = getAdmin(
                "http://localhost:" + port + "/admin/melt-saga/by-id/saga-missing");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void admin_endpoint_returns_401_without_auth_T300() {
        seed("saga-401", "quote-401", MeltSagaState.COMPLETED);
        // Plain RestTemplate (no Basic auth) — expect 401 Unauthorized.
        ResponseEntity<String> response;
        try {
            response = restTemplate.getForEntity(
                    "http://localhost:" + port + "/admin/melt-saga/by-id/saga-401", String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            response = ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void admin_endpoint_returns_401_with_wrong_password_T300() {
        seed("saga-401b", "quote-401b", MeltSagaState.COMPLETED);
        HttpHeaders bad = new HttpHeaders();
        bad.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString("admin-it:wrong-password".getBytes(StandardCharsets.UTF_8)));
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(
                    "http://localhost:" + port + "/admin/melt-saga/by-id/saga-401b",
                    HttpMethod.GET, new HttpEntity<>(bad), String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            response = ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void public_NUT_endpoint_remains_open_after_security_config_lands() {
        // Spec 002 T311 — the SecurityFilterChain must lock /admin/** only.
        // /v1/info MUST remain reachable without credentials so the mint
        // contract is preserved for wallet clients.
        ResponseEntity<String> response;
        try {
            response = restTemplate.getForEntity(
                    "http://localhost:" + port + "/v1/info", String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            response = ResponseEntity.status(e.getStatusCode()).build();
        }
        // 200 OK on a configured mint; any non-401 confirms the filter
        // chain did NOT lock the public endpoint.
        assertThat(response.getStatusCode().value()).isNotEqualTo(401);
        assertThat(response.getStatusCode().value()).isNotEqualTo(403);
    }

    @Test
    void markResolved_appends_transition_without_changing_state() throws Exception {
        seed("saga-q-3", "quote-q-3", MeltSagaState.PAYMENT_SENT_BURN_FAILED);
        int beforeSize = meltSagaTransitionRepository.findTimeline("saga-q-3").size();

        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                MAPPER.writeValueAsString(Map.of(
                        "actor", "operator:alice",
                        "reason", "manually reconciled with provider")),
                headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/admin/melt-saga/saga-q-3/mark-resolved",
                HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // currentState must not have changed.
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("currentState").asText()).isEqualTo("PAYMENT_SENT_BURN_FAILED");
        // Transition timeline has a new entry; original entries are intact.
        List<MeltSagaTransitionEntity> after =
                meltSagaTransitionRepository.findTimeline("saga-q-3");
        assertThat(after).hasSize(beforeSize + 1);
        MeltSagaTransitionEntity newEntry = after.get(after.size() - 1);
        assertThat(newEntry.getActor()).isEqualTo("operator:alice");
        assertThat(newEntry.getReason()).isEqualTo("manually reconciled with provider");
        assertThat(newEntry.getFromState()).isEqualTo(MeltSagaState.PAYMENT_SENT_BURN_FAILED);
        assertThat(newEntry.getToState()).isEqualTo(MeltSagaState.PAYMENT_SENT_BURN_FAILED);
    }

    private void seed(String sagaId, String quoteId, MeltSagaState state) {
        MeltSagaEntity saga = new MeltSagaEntity();
        saga.setMeltSagaId(sagaId);
        saga.setQuoteId(quoteId);
        saga.setInvoiceAmount(100L);
        saga.setExactFeeReserve(5L);
        saga.setInputAmount(105L);
        saga.setProofCount(2);
        saga.setProvider("test-provider");
        saga.setCurrentState(state);
        meltSagaRepository.save(saga);

        // Seed two timeline entries so the IT can assert the full history.
        MeltSagaTransitionEntity t1 = new MeltSagaTransitionEntity();
        t1.setMeltSagaId(sagaId);
        t1.setSeq(1);
        t1.setFromState(null);
        t1.setToState(MeltSagaState.PROOFS_HELD);
        t1.setReason("initial");
        t1.setActor("system");
        t1.setAt(Instant.now().minusSeconds(60));
        meltSagaTransitionRepository.save(t1);

        if (state != MeltSagaState.PROOFS_HELD) {
            MeltSagaTransitionEntity t2 = new MeltSagaTransitionEntity();
            t2.setMeltSagaId(sagaId);
            t2.setSeq(2);
            t2.setFromState(MeltSagaState.PROOFS_HELD);
            t2.setToState(state);
            t2.setReason("transition for test");
            t2.setActor("system");
            t2.setAt(Instant.now().minusSeconds(30));
            meltSagaTransitionRepository.save(t2);
        }
    }

    private static ResponseEntity<String> catchHttpStatus(java.util.function.Supplier<ResponseEntity<String>> call) {
        try {
            return call.get();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
    }
}
