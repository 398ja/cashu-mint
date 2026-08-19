package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;

/**
 * Rotation, asserted against the mint rather than the admin's own echo.
 *
 * <p>Blocked by the vault schema. {@code t_keyset} carries
 * {@code UNIQUE (unit, mint_id)}, so a mint may hold exactly one keyset per unit
 * — the replacement cannot exist alongside the keyset it replaces. Rotation
 * therefore fails at the insert with a constraint violation, and the admin
 * records KEY_ROTATION_FAILED and leaves the existing keyset active.
 *
 * <p>Deleting the old keyset instead is not an option: ADR-0004 requires an
 * archived keyset to keep verifying and redeeming indefinitely, and removing it
 * would strand every token it signed. The fix belongs in cashu-vault — the
 * unique index needs to be dropped or made partial on {@code archived = false};
 * {@code idx_keyset_key_set_mint_unq} already provides the identity constraint
 * that matters. Re-enable this class once that lands.
 *
 * <p>The mint advertises only its own mint's keysets, which is why this rotates
 * the mint id the stack preloads rather than a freshly created one.
 */
@Disabled("Blocked by cashu-vault t_keyset UNIQUE (unit, mint_id): a rotated keyset "
    + "cannot coexist with the one it replaces. See the class javadoc.")
class KeyRotationE2EIT extends AbstractAdminE2EIT {

    /** The mint id the stack preloads, and the only one this mint serves. */
    private static final String SERVED_MINT_ID = "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae";

    // Verifies rotation replaces the mint's active keyset and retires the previous
    // one, read from the mint itself rather than from the admin's response.
    @Test
    void shouldReplaceTheMintsActiveKeysetAndRetireThePrevious() {
        registerServedMint();
        final String previous = activeKeysetId();

        rotate("rotate the served mint");

        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(activeKeysetId())
                .as("the mint must advertise a different active keyset after rotation")
                .isNotEqualTo(previous));

        // The retired keyset must still be advertised, and inactive: tokens it
        // signed stay redeemable, which is the whole point of archiving rather
        // than deleting. ADR-0004.
        assertThat(keysetIds()).contains(previous);
        assertThat(isActive(previous))
            .as("the replaced keyset must be retired, not removed")
            .isFalse();
    }

    // Verifies the audit trail names both keysets, so an operator can reconstruct
    // the key history from the control record alone.
    @Test
    void shouldRecordBothKeysetIdsInTheAuditTrail() {
        registerServedMint();
        final String previous = activeKeysetId();

        rotate("rotate for the audit trail");

        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> {
                final JsonNode control = latestRotationControl();
                assertThat(control.path("status").asText()).isEqualTo("KEY_ROTATION_COMPLETED");
                assertThat(control.path("outcome").asText())
                    .contains(previous)
                    .contains(activeKeysetId());
            });
    }

    // Verifies a second rotation is a distinct rotation rather than a repeat: each
    // is keyed on its own control id, so neither reuses the other's keyset.
    @Test
    void shouldProduceADistinctKeysetForEachRotation() {
        registerServedMint();
        final String original = activeKeysetId();

        rotate("first rotation");
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(activeKeysetId()).isNotEqualTo(original));
        final String afterFirst = activeKeysetId();

        rotate("second rotation");
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(activeKeysetId()).isNotEqualTo(afterFirst));

        assertThat(keysetIds())
            .as("every keyset ever active must remain advertised for redemption")
            .contains(original, afterFirst, activeKeysetId());
    }

    private void registerServedMint() {
        // Provisioning is idempotent and derives the same keyset the stack
        // preloaded, so this only gives the admin a mint aggregate to rotate.
        adminApiClient().post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", SERVED_MINT_ID,
                "requestedBy", actor("Ops Admin"),
                "metadata", Map.of(
                    "displayName", "served mint",
                    "description", "the mint this stack serves",
                    "tags", java.util.List.of("e2e")),
                "configuration", Map.of("versionTag", "v1", "name", "served")),
            MINT_ADMIN_ROLE);

        Awaitility.await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> {
                final ResponseEntity<JsonNode> detail = adminApiClient().get(
                    "/admin/lifecycle/mints/" + SERVED_MINT_ID, MINT_ADMIN_ROLE);
                assertThat(detail.getBody().path("lifecycleState").asText()).isEqualTo("PROVISIONED");
            });
    }

    private void rotate(final String reason) {
        final ResponseEntity<JsonNode> response = adminApiClient().post(
            "/admin/operations/mints/" + SERVED_MINT_ID + "/keys/rotate",
            Map.of(
                "reason", reason,
                "durationMinutes", 5,
                "requestedBy", actor("Ops Admin")),
            OPS_ADMIN_ROLE);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private JsonNode latestRotationControl() {
        final ResponseEntity<JsonNode> controls = adminApiClient().get(
            "/admin/operations/mints/" + SERVED_MINT_ID + "/controls", OPS_ADMIN_ROLE);
        for (final JsonNode control : controls.getBody().path("items")) {
            if ("KEY_ROTATION".equals(control.path("controlType").asText())) {
                return control;
            }
        }
        throw new AssertionError("no key rotation control recorded");
    }

    private String activeKeysetId() {
        for (final JsonNode keyset : mintApiClient().get("/v1/keysets").getBody().path("keysets")) {
            if (keyset.path("active").asBoolean()) {
                return keyset.path("id").asText();
            }
        }
        throw new AssertionError("the mint advertises no active keyset");
    }

    private boolean isActive(final String keysetId) {
        for (final JsonNode keyset : mintApiClient().get("/v1/keysets").getBody().path("keysets")) {
            if (keysetId.equals(keyset.path("id").asText())) {
                return keyset.path("active").asBoolean();
            }
        }
        throw new AssertionError("keyset no longer advertised: " + keysetId);
    }

    private Set<String> keysetIds() {
        final Set<String> ids = new HashSet<>();
        for (final JsonNode keyset : mintApiClient().get("/v1/keysets").getBody().path("keysets")) {
            ids.add(keyset.path("id").asText());
        }
        return ids;
    }
}
