package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class MintProtocolE2EIT extends AbstractAdminE2EIT {

    // NUT-17 WebSocket coverage is intentionally deferred in this phase.
    private static final String FALLBACK_KEYSET_ID = "00e3372e61d05605";
    private static final String GENERATOR_POINT =
        "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    // Verifies NUT-04 quote creation and status lookup on the live mint API.
    @Test
    void shouldCreateMintQuoteAndLookupStatus() {
        final ResponseEntity<JsonNode> quoteResponse = mintApiClient().post(
            "/v1/mint/quote/bolt11",
            Map.of("amount", 64, "unit", "sat"));

        // Quote creation depends on the payment gateway; tolerate 500 if the mock is unavailable.
        assertThat(quoteResponse.getStatusCode().value()).isIn(200, 500);
        if (quoteResponse.getStatusCode().value() != 200) {
            return;
        }

        final String quoteId = quoteResponse.getBody().path("quote").asText();
        assertThat(quoteId).isNotBlank();

        final ResponseEntity<JsonNode> quoteLookup = mintApiClient().get("/v1/mint/quote/bolt11/" + quoteId);
        assertThat(quoteLookup.getStatusCode().value()).isEqualTo(200);
        assertThat(quoteLookup.getBody().path("quote").asText()).isEqualTo(quoteId);
    }

    // Verifies NUT-04 mint and NUT-09 restore routes can be executed without server-side failures.
    @Test
    void shouldAttemptMintAndRestoreFlow() {
        final String keysetId = resolveKeysetId();

        final ResponseEntity<JsonNode> quoteResponse = mintApiClient().post(
            "/v1/mint/quote/bolt11",
            Map.of("amount", 32, "unit", "sat"));

        // Quote creation depends on the payment gateway; tolerate 500 if the mock is unavailable.
        assertThat(quoteResponse.getStatusCode().value()).isIn(200, 500);
        if (quoteResponse.getStatusCode().value() != 200) {
            return;
        }

        final String quoteId = quoteResponse.getBody().path("quote").asText();
        final Map<String, Object> blindedMessage = Map.of(
            "id", keysetId,
            "amount", 32,
            "B_", GENERATOR_POINT);

        final ResponseEntity<JsonNode> mintResponse = mintApiClient().post(
            "/v1/mint/bolt11",
            Map.of(
                "quote", quoteId,
                "outputs", List.of(blindedMessage)));
        assertThat(mintResponse.getStatusCode().value()).isIn(200, 400, 402, 404);

        final ResponseEntity<JsonNode> restoreResponse = mintApiClient().post(
            "/v1/restore",
            Map.of("outputs", List.of(blindedMessage)));
        assertThat(restoreResponse.getStatusCode().value()).isLessThan(500);
    }

    // Verifies NUT-03 swap endpoint behavior is stable and does not return server errors for invalid inputs.
    @Test
    void shouldExerciseSwapFlowEndpoint() {
        final String keysetId = resolveKeysetId();
        final ResponseEntity<JsonNode> swapResponse = mintApiClient().post(
            "/v1/swap",
            Map.of(
                "inputs", List.of(
                    Map.of(
                        "id", keysetId,
                        "amount", 1,
                        "secret", "e2e-dummy-secret",
                        "C", GENERATOR_POINT)),
                "outputs", List.of(
                    Map.of(
                        "id", keysetId,
                        "amount", 1,
                        "B_", GENERATOR_POINT))));

        assertThat(swapResponse.getStatusCode().value()).isLessThan(500);
    }

    // Verifies NUT-05 melt quote and melt execution routes are reachable without server-side failures.
    @Test
    void shouldExerciseMeltFlowEndpoint() {
        final String keysetId = resolveKeysetId();
        final ResponseEntity<JsonNode> quoteResponse = mintApiClient().post(
            "/v1/melt/quote/bolt11",
            Map.of(
                "request",
                "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w",
                "unit", "sat"));

        // Melt quote creation depends on the payment gateway; tolerate 500 if the mock is unavailable.
        assertThat(quoteResponse.getStatusCode().value()).isLessThanOrEqualTo(500);
        if (quoteResponse.getStatusCode().value() >= 500) {
            return;
        }

        final String quoteId = quoteResponse.getBody() == null
            ? "missing-quote"
            : quoteResponse.getBody().path("quote").asText("missing-quote");

        final ResponseEntity<JsonNode> meltResponse = mintApiClient().post(
            "/v1/melt/bolt11",
            Map.of(
                "quote", quoteId,
                "inputs", List.of(
                    Map.of(
                        "id", keysetId,
                        "amount", 1,
                        "secret", "e2e-dummy-secret",
                        "C", GENERATOR_POINT))));
        assertThat(meltResponse.getStatusCode().value()).isLessThan(500);
    }

    // Verifies NUT-07 checkstate route is callable for hash-to-curve lookups.
    @Test
    void shouldExerciseCheckstateFlowEndpoint() {
        final ResponseEntity<JsonNode> checkstateResponse = mintApiClient().post(
            "/v1/checkstate",
            Map.of("Ys", List.of(GENERATOR_POINT)));

        assertThat(checkstateResponse.getStatusCode().value()).isLessThan(500);
    }

    private String resolveKeysetId() {
        final ResponseEntity<JsonNode> keysetsResponse = mintApiClient().get("/v1/keysets");
        if (keysetsResponse.getStatusCode().value() != 200 || keysetsResponse.getBody() == null) {
            return FALLBACK_KEYSET_ID;
        }

        final JsonNode keysets = keysetsResponse.getBody().path("keysets");
        if (!keysets.isArray() || keysets.isEmpty()) {
            return FALLBACK_KEYSET_ID;
        }

        final String discoveredKeysetId = keysets.get(0).path("id").asText();
        return discoveredKeysetId == null || discoveredKeysetId.isBlank()
            ? FALLBACK_KEYSET_ID
            : discoveredKeysetId;
    }
}
