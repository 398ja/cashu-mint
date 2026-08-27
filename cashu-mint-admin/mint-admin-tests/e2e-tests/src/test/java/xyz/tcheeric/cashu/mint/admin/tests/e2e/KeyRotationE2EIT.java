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
 * <p>Rotation itself works: against a vault carrying the fix from 398ja/cashu-vault#126 the saga
 * completes and records "Keyset X replaces [Y]". What this class cannot yet observe is the mint's
 * side of it.
 *
 * <p>The stack runs the mint with {@code PreloadMintLoadService}, which is on by default ({@code
 * mint.preload.enabled}, {@code matchIfMissing = true}) and serves keysets from a JSON file rather
 * than the vault. The vault-backed {@code DefaultMintLoadService} is {@code @Profile({"!dev",
 * "!test"})} and the stack runs the mint under {@code dev}. So a rotation lands in the vault and
 * {@code /v1/keysets} never changes — not because rotation failed, but because this mint is not
 * reading from where it was written.
 *
 * <p>Enabling this class means running the stack's mint against the vault: a non-dev profile and
 * {@code mint.preload.enabled=false}. That also removes the preloaded keyset the other E2E tests
 * rely on at startup, so it is a change to the stack rather than to this file.
 *
 * <p>The mint advertises only its own mint's keysets, which is why this rotates the mint id the
 * stack preloads rather than a freshly created one.
 */
@Disabled(
    "The stack's mint loads keysets from preload JSON, not the vault, so a "
        + "rotation is invisible to /v1/keysets. Rotation itself succeeds. See the class javadoc.")
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
        .untilAsserted(
            () ->
                assertThat(activeKeysetId())
                    .as("the mint must advertise a different active keyset after rotation")
                    .isNotEqualTo(previous));

    // The retired keyset must still be advertised, and inactive: tokens it
    // signed stay redeemable, which is the whole point of archiving rather
    // than deleting. ADR-0004.
    assertThat(keysetIds()).contains(previous);
    assertThat(isActive(previous)).as("the replaced keyset must be retired, not removed").isFalse();
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
        .untilAsserted(
            () -> {
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
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(activeKeysetId()).isNotEqualTo(original));
    final String afterFirst = activeKeysetId();

    rotate("second rotation");
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(activeKeysetId()).isNotEqualTo(afterFirst));

    assertThat(keysetIds())
        .as("every keyset ever active must remain advertised for redemption")
        .contains(original, afterFirst, activeKeysetId());
  }

  private void registerServedMint() {
    // Provisioning is idempotent and derives the same keyset the stack
    // preloaded, so this only gives the admin a mint aggregate to rotate.
    adminApiClient()
        .post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", SERVED_MINT_ID,
                "metadata",
                    Map.of(
                        "displayName", "served mint",
                        "description", "the mint this stack serves",
                        "tags", java.util.List.of("e2e")),
                "configuration", Map.of("versionTag", "v1", "name", "served")),
            MINT_ADMIN_ROLE);

    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              final ResponseEntity<JsonNode> detail =
                  adminApiClient().get("/admin/lifecycle/mints/" + SERVED_MINT_ID, MINT_ADMIN_ROLE);
              assertThat(detail.getBody().path("lifecycleState").asText()).isEqualTo("PROVISIONED");
            });
  }

  private void rotate(final String reason) {
    final ResponseEntity<JsonNode> response =
        adminApiClient()
            .post(
                "/admin/operations/mints/" + SERVED_MINT_ID + "/keys/rotate",
                Map.of("reason", reason, "durationMinutes", 5),
                OPS_ADMIN_ROLE);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  private JsonNode latestRotationControl() {
    final ResponseEntity<JsonNode> controls =
        adminApiClient()
            .get("/admin/operations/mints/" + SERVED_MINT_ID + "/controls", OPS_ADMIN_ROLE);
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
