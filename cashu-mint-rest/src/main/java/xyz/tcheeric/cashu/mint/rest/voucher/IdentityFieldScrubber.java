package xyz.tcheeric.cashu.mint.rest.voucher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec 004 T400 / FR-005 — walk a JSON response body and replace any
 * field whose key matches a known identity-bearing name with the
 * HMAC-SHA-256 hash of its string value. Used by
 * {@link VoucherIdempotencyKeyFilter} before persisting the cached
 * response into {@code voucher_idempotency_key.response_body_json}
 * so a DB dump of that cache never reveals raw npubs.
 *
 * <h2>Field name list</h2>
 * Both snake_case and camelCase variants of {@code customer_id} and
 * {@code merchant_id} are scrubbed. The set is deliberately small —
 * any new identity field added to the response contract MUST be added
 * here too (caught by the {@code DisclosureDocSchemaContractTest} that
 * also lists every identity column).
 *
 * <h2>Idempotent on already-hashed values</h2>
 * Hashing a 64-char hex digest produces a different hash, so re-running
 * the scrubber would change the value. The implementation skips strings
 * that already match {@code ^[0-9a-f]{64}$} — re-scrubs are no-ops.
 *
 * <h2>Null short-circuit</h2>
 * If the hasher is null (legacy context) OR the field value is null /
 * empty, the field is left unchanged. Production deploys have the
 * hasher wired via {@code MintIntegrityContext.installIdentityHasher}.
 */
@Slf4j
public final class IdentityFieldScrubber {

    private static final Set<String> IDENTITY_FIELD_NAMES = Set.of(
            "customer_id", "customerId",
            "merchant_id", "merchantId");

    private static final java.util.regex.Pattern HASHED = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IdentityFieldScrubber() {
    }

    /**
     * Parse the JSON, scrub identity fields in place, return the
     * re-serialised string. Returns the input verbatim if hasher is
     * null OR if the input isn't valid JSON (no scrub possible).
     */
    public static String scrub(String json, IdentityHasher hasher) {
        if (hasher == null || json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            walk(root, hasher);
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            // Not valid JSON — pass through unchanged. The cache row
            // will then store the original body; this is a conservative
            // posture (we'd rather log + pass than risk dropping a
            // successful response).
            log.warn("voucher_idempotency_scrub failed to parse JSON; persisting unchanged ({} bytes)",
                    json.length());
            return json;
        }
    }

    private static void walk(JsonNode node, IdentityHasher hasher) {
        if (node instanceof ObjectNode obj) {
            // Mutate first; collect renames to avoid ConcurrentModification.
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (IDENTITY_FIELD_NAMES.contains(name) && value.isTextual()) {
                    String raw = value.asText();
                    if (raw.isEmpty() || HASHED.matcher(raw).matches()) {
                        continue; // already hashed or empty — leave alone
                    }
                    String hashed = hasher.hash(raw);
                    if (hashed != null) {
                        obj.set(name, TextNode.valueOf(hashed));
                    }
                } else {
                    walk(value, hasher);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                walk(child, hasher);
            }
        }
    }

    /** Test helper — list the identity field names this scrubber recognises. */
    public static List<String> identityFieldNames() {
        return List.copyOf(IDENTITY_FIELD_NAMES);
    }
}
