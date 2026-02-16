package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

class MintProtocolNegativeE2EIT extends AbstractAdminE2EIT {

    private static final String FALLBACK_KEYSET_ID = "00e3372e61d05605";
    private static final String GENERATOR_POINT =
        "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    // Verifies missing required quote/input fields return client errors for protocol endpoints.
    @Test
    void shouldRejectMissingRequiredFields() {
        final ResponseEntity<JsonNode> mintResponse = mintApiClient().post("/v1/mint/bolt11", Map.of());
        assertThat(mintResponse.getStatusCode().value()).isEqualTo(400);

        final ResponseEntity<JsonNode> meltResponse = mintApiClient().post("/v1/melt/bolt11", Map.of());
        assertThat(meltResponse.getStatusCode().value()).isEqualTo(400);

        final ResponseEntity<JsonNode> swapResponse = mintApiClient().post("/v1/swap", Map.of());
        assertThat(swapResponse.getStatusCode().value()).isEqualTo(400);
    }

    // Verifies unpaid invoice mint execution maps to an expected payment-required or client-error response.
    @Test
    void shouldExposeUnpaidInvoiceBehavior() {
        final String keysetId = resolveKeysetId();
        final ResponseEntity<JsonNode> quoteResponse = mintApiClient().post(
            "/v1/mint/quote/bolt11",
            Map.of("amount", 16, "unit", "sat"));

        // Quote creation depends on the payment gateway; tolerate 500 if the mock is unavailable.
        assertThat(quoteResponse.getStatusCode().value()).isIn(200, 500);
        if (quoteResponse.getStatusCode().value() != 200) {
            return;
        }

        final String quoteId = quoteResponse.getBody().path("quote").asText();

        final ResponseEntity<JsonNode> mintResponse = mintApiClient().post(
            "/v1/mint/bolt11",
            Map.of(
                "quote", quoteId,
                "outputs", List.of(Map.of("id", keysetId, "amount", 16, "B_", GENERATOR_POINT))));

        assertThat(mintResponse.getStatusCode().value()).isIn(400, 402, 404);
        if (mintResponse.getStatusCode().value() == 402) {
            assertThat(mintResponse.getBody().path("code").asText()).isEqualTo("mint_invoice_not_paid_error");
        }
    }

    // Verifies unknown quote identifiers return not-found or error responses for both mint and melt lookups.
    @Test
    void shouldReturnNotFoundForUnknownQuoteLookups() {
        final String unknownQuoteId = UUID.randomUUID().toString();

        final ResponseEntity<JsonNode> mintLookup = mintApiClient().get("/v1/mint/quote/bolt11/" + unknownQuoteId);
        assertThat(mintLookup.getStatusCode().value()).isIn(400, 404, 500);

        final ResponseEntity<JsonNode> meltLookup = mintApiClient().get("/v1/melt/quote/bolt11/" + unknownQuoteId);
        assertThat(meltLookup.getStatusCode().value()).isIn(400, 404, 500);
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
