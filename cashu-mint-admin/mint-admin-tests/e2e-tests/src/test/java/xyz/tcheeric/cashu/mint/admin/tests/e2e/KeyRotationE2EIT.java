package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AdminE2EClient;

/**
 * Rotation, asserted against the mint rather than the admin's own echo.
 *
 * <p>This is the test that says the admin and the mint are the same system: the admin writes a
 * rotation to the vault, and the mint is asked what it now advertises. Asserting on the admin's own
 * response would pass even if the mint never saw it.
 *
 * <p>The stack runs the mint with {@code MINT_PRELOAD_ENABLED=false}, so the vault-backed
 * {@code DefaultMintLoadService} serves {@code /v1/keysets} rather than
 * {@code PreloadMintLoadService} reading a fixed keyset from JSON. {@code VaultPreloadSeeder}
 * still seeds that JSON into the vault at startup, so the keyset the suite rotates is there.
 *
 * <p>The mint advertises only its own mint's keysets, which is why this rotates the mint id the
 * stack preloads rather than a freshly created one.
 */
class KeyRotationE2EIT extends AbstractAdminE2EIT {

  /** The mint id the stack preloads, and the one these tests rotate. */
  private static final String SERVED_MINT_ID = "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae";

  /** The unit that mint's keyset serves; the vault may hold others. */
  private static final String UNIT = "sat";

  /** Names the keyset a completed rotation produced, as recorded on the control. */
  private static final Pattern ROTATION_OUTCOME = Pattern.compile("Keyset (\\w+) replaces");

  /** Names the first keyset a completed rotation retired. */
  private static final Pattern RETIRED_KEYSET = Pattern.compile("replaces \\[(\\w+)");

  /**
   * One signed-in client for the whole class. {@code adminApiClient()} opens a fresh NAP
   * session on every call, and these tests poll the admin while waiting for a rotation to
   * land; re-authenticating per poll trips NAP's rate limiter and fails the suite with 429
   * rather than with anything about rotation.
   */
  private AdminE2EClient admin;

  /** The mint is created once for the class; creating it twice is a conflict. */
  private static boolean registered;

  @BeforeEach
  void signIn() {
    admin = adminApiClient();
  }

  // Verifies rotation replaces the mint's active keyset and retires the previous
  // one, read from the mint itself rather than from the admin's response.
  @Test
  void shouldReplaceTheMintsActiveKeysetAndRetireThePrevious() {
    registerServedMint();
    final String controlId = rotate("rotate the served mint");
    final String replacement = awaitRotation(controlId);
    // The keyset this rotation actually retired, taken from its own record rather
    // than from a read of the mint taken before it ran.
    final String previous = retiredKeySetId(controlId);

    assertThat(replacement)
        .as("the rotation must produce a keyset other than the one it replaced")
        .isNotEqualTo(previous);
    // The replacement must actually reach the mint, which is the whole point: the
    // admin wrote it to the vault and the mint reads its keysets from there.
    // Advertised rather than active: the tests in this class share one mint, so a
    // later rotation may already have superseded this keyset by the time this runs.
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(keysetIds())
                    .as("the mint must advertise the keyset the rotation produced")
                    .contains(replacement));

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
    final String controlId = rotate("rotate for the audit trail");
    final String replacement = awaitRotation(controlId);

    // Both ends of the replacement, so an operator can reconstruct the key history
    // from the control record alone: the keyset that now signs, and a non-empty list
    // of the ones it retired. Which keyset was active when this test started is not
    // asserted — every test here rotates the same mint, so that is a shared value
    // this test does not own.
    final String outcome = rotationControl(controlId).path("outcome").asText();
    assertThat(outcome).contains(replacement);
    assertThat(outcome).matches("Keyset \\w+ replaces \\[\\w+.*\\]");
  }

  // Verifies a second rotation is a distinct rotation rather than a repeat: each
  // is keyed on its own control id, so neither reuses the other's keyset.
  @Test
  void shouldProduceADistinctKeysetForEachRotation() {
    registerServedMint();
    final String original = activeKeysetId();

    final String afterFirst = awaitRotation(rotate("first rotation"));
    final String afterSecond = awaitRotation(rotate("second rotation"));

    assertThat(afterSecond)
        .as("each rotation is keyed on its own control id, so neither reuses the other's keyset")
        .isNotEqualTo(afterFirst);

    assertThat(keysetIds())
        .as("every keyset ever active must remain advertised for redemption")
        .contains(original, afterFirst, afterSecond);
  }

  private void registerServedMint() {
    // Registered once for the class: creating a mint is not idempotent, and each
    // test rotates whatever keyset is active rather than assuming a fresh mint.
    if (registered) {
      return;
    }
    admin
        .post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", SERVED_MINT_ID,
                "metadata",
                    Map.of(
                        "displayName", "served mint",
                        "description", "the mint this stack serves",
                        "tags", java.util.List.of("e2e")),
                // The unit and denominations the rotation saga reads. They match the
                // keyset VaultPreloadSeeder seeds, so the first rotation replaces that
                // keyset with one covering the same denominations rather than a
                // narrower default set.
                "configuration",
                    Map.of(
                        "versionTag", "v1",
                        "cashu.unit", "sat",
                        "cashu.denominations", "1,2,4,8,16,32,64,128,256,512,1024")));

    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              final ResponseEntity<JsonNode> detail =
                  admin.get("/admin/lifecycle/mints/" + SERVED_MINT_ID);
              assertThat(detail.getBody().path("lifecycleState").asText()).isEqualTo("PROVISIONED");
            });
    registered = true;
  }

  /** Starts a rotation and returns the id of the control that drives it. */
  private String rotate(final String reason) {
    final ResponseEntity<JsonNode> response =
        admin
            .post(
                "/admin/operations/mints/" + SERVED_MINT_ID + "/keys/rotate",
                Map.of("reason", reason, "durationMinutes", 5));
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return response.getBody().path("controlId").asText();
  }

  /**
   * Waits for a rotation to complete and answers the keyset it produced.
   *
   * <p>Read from the control record rather than from whatever the mint currently
   * advertises: every test in this class rotates the same mint, so the active keyset
   * may already belong to another test's rotation by the time this one is asserted.
   */
  private String awaitRotation(final String controlId) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(rotationControl(controlId).path("status").asText())
                    .isEqualTo("KEY_ROTATION_COMPLETED"));
    final Matcher matcher =
        ROTATION_OUTCOME.matcher(rotationControl(controlId).path("outcome").asText());
    assertThat(matcher.find()).as("the rotation must record the keyset it produced").isTrue();
    return matcher.group(1);
  }

  /**
   * The control this test started, found by id.
   *
   * <p>Every test in this class rotates the same mint, so "the newest rotation" is
   * whichever test ran last rather than this one.
   */
  private JsonNode rotationControl(final String controlId) {
    final ResponseEntity<JsonNode> controls =
        admin.get("/admin/operations/mints/" + SERVED_MINT_ID + "/controls");
    for (final JsonNode control : controls.getBody().path("items")) {
      if (controlId.equals(control.path("controlId").asText())) {
        return control;
      }
    }
    throw new AssertionError("no control recorded for " + controlId);
  }

  /** The keyset a completed rotation retired, read from its own control record. */
  private String retiredKeySetId(final String controlId) {
    final Matcher matcher =
        RETIRED_KEYSET.matcher(rotationControl(controlId).path("outcome").asText());
    assertThat(matcher.find()).as("the rotation must record the keyset it retired").isTrue();
    return matcher.group(1);
  }

  /**
   * The active keyset for the unit under test.
   *
   * <p>Scoped to {@code sat} rather than taking the first active keyset of any unit: the
   * mint serves every mint in the shared vault, so another suite's {@code usd} keyset
   * would otherwise be picked up and rotation would look like it had not happened.
   */
  private String activeKeysetId() {
    for (final JsonNode keyset : mintApiClient().get("/v1/keysets").getBody().path("keysets")) {
      if (keyset.path("active").asBoolean() && UNIT.equals(keyset.path("unit").asText())) {
        return keyset.path("id").asText();
      }
    }
    throw new AssertionError("the mint advertises no active " + UNIT + " keyset");
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
