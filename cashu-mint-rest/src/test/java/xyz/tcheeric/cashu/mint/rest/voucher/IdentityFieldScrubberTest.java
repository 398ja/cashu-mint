package xyz.tcheeric.cashu.mint.rest.voucher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T400 / FR-005 — unit-level coverage of the JSON scrubber.
 * The IT in {@code spec004/IdempotencyCacheScrubbedIT} exercises the
 * filter end-to-end; this test verifies the scrubber primitive in
 * isolation.
 */
class IdentityFieldScrubberTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stub hasher — appends a fixed suffix so we can assert hashing happened. */
    private static final IdentityHasher STUB = value -> {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "h_" + value;
    };

    @Test
    void scrubsCustomerIdAtTopLevel() throws Exception {
        String input = "{\"customer_id\":\"npub1abc\",\"face_value\":1000}";
        String out = IdentityFieldScrubber.scrub(input, STUB);
        JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("customer_id").asText()).isEqualTo("h_npub1abc");
        assertThat(node.get("face_value").asLong()).isEqualTo(1000L);
    }

    @Test
    void scrubsCamelCaseAndNested() throws Exception {
        String input = "{\"voucher\":{\"customerId\":\"npub1xyz\",\"merchantId\":\"npub1mer\"},\"amount\":42}";
        String out = IdentityFieldScrubber.scrub(input, STUB);
        JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("voucher").get("customerId").asText()).isEqualTo("h_npub1xyz");
        assertThat(node.get("voucher").get("merchantId").asText()).isEqualTo("h_npub1mer");
        assertThat(node.get("amount").asLong()).isEqualTo(42L);
    }

    @Test
    void scrubsInsideArrays() throws Exception {
        String input = "{\"items\":[{\"customer_id\":\"npub1one\"},{\"customer_id\":\"npub1two\"}]}";
        String out = IdentityFieldScrubber.scrub(input, STUB);
        JsonNode arr = MAPPER.readTree(out).get("items");
        assertThat(arr.get(0).get("customer_id").asText()).isEqualTo("h_npub1one");
        assertThat(arr.get(1).get("customer_id").asText()).isEqualTo("h_npub1two");
    }

    @Test
    void leavesAlreadyHashedValuesAlone() throws Exception {
        // Idempotent — re-scrubbing a 64-char hex value is a no-op.
        String alreadyHashed = "a".repeat(64);
        String input = "{\"customer_id\":\"" + alreadyHashed + "\"}";
        String out = IdentityFieldScrubber.scrub(input, STUB);
        assertThat(MAPPER.readTree(out).get("customer_id").asText())
                .isEqualTo(alreadyHashed);
    }

    @Test
    void leavesEmptyAndWhitespaceAlone() throws Exception {
        String input = "{\"customer_id\":\"\",\"merchant_id\":null}";
        String out = IdentityFieldScrubber.scrub(input, STUB);
        JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("customer_id").asText()).isEmpty();
        assertThat(node.get("merchant_id").isNull()).isTrue();
    }

    @Test
    void passesThroughInvalidJson() {
        String invalid = "{not json}";
        assertThat(IdentityFieldScrubber.scrub(invalid, STUB)).isEqualTo(invalid);
    }

    @Test
    void passesThroughWhenHasherIsNull() {
        String input = "{\"customer_id\":\"npub1abc\"}";
        assertThat(IdentityFieldScrubber.scrub(input, null)).isEqualTo(input);
    }

    @Test
    void leavesOtherFieldsAlone() throws Exception {
        // Non-identity fields named similarly (e.g. customerLookup, merchant_url)
        // MUST NOT be scrubbed.
        String input = "{\"customer_lookup\":\"npub1foo\",\"merchant_url\":\"https://m.example\"}";
        String out = IdentityFieldScrubber.scrub(input, STUB);
        JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("customer_lookup").asText()).isEqualTo("npub1foo");
        assertThat(node.get("merchant_url").asText()).isEqualTo("https://m.example");
    }
}
