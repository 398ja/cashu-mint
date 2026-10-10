package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * cashu-mint#540 review §1.2a: an honest redeemer must be able to give a declaration back when the
 * credit it stood for is undone (refund, reversal, a downstream failure after the swap).
 *
 * <p>Without that, an honest redeemer is a free griefing oracle: the attacker relabels plain sats
 * onto a victim's coupon, has it credited and declared, then gets refunded. The victim's cap is
 * consumed for nothing and the real holder is refused.
 *
 * <p>The release names the swap it reverses by its NUT-19 request fingerprint, which the redeemer
 * already holds (it built the request). It is idempotent, and it can never give back more than
 * that one swap declared.
 */
@TestPropertySource(properties = {
    "cashu.mint.voucher.redemption-cap=enforce",
    "cashu.mint.voucher.issuer-binding=enforce",
    "cashu.mint.voucher.trusted-signers=" + VoucherRedemptionFixtures.ISSUER_PUB_X,
    "cashu.mint.voucher.redeemer-tokens=" + VoucherRedemptionReleaseIT.REDEEMER + ",other-service-token",
})
class VoucherRedemptionReleaseIT extends VoucherRedemptionFixtures {

  static final String REDEEMER = "it-release-redeemer-token";
  static final String RELEASE_PATH = "/v1/voucher-redemptions/release";
  static final String RECEIPT_HEADER = "X-Cashu-Voucher-Redemption-Receipt";

  @Override
  String redeemerToken() {
    return REDEEMER;
  }

  /**
   * The griefing attack end to end. The attacker relabels 10 plain sats onto the victim's coupon,
   * an honest redeemer credits and declares it, then refunds the attacker and releases the
   * declaration. The victim's full redemption is then accepted.
   */
  @Test
  void aRefundReleaseRestoresTheVictimsCap() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    ResponseEntity<String> attack = post("/v1/swap", swapBody(voucherProofs(blob, 8, 2), outputs(8, 2)),
        redemptionHeaders(voucherId + ":10", REDEEMER));
    assertThat(attack.getStatusCode().is2xxSuccessful()).as("body=%s", attack.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);

    ResponseEntity<String> victimBefore = redeem(voucherId, 10, voucherProofs(blob, 8, 2));
    assertThat(victimBefore.getStatusCode().is4xxClientError())
        .as("while the attacker's credit stands the cap is spent, body=%s", victimBefore.getBody()).isTrue();

    ResponseEntity<String> release = release(voucherId, receipt(attack), REDEEMER);
    assertThat(release.getStatusCode().is2xxSuccessful()).as("body=%s", release.getBody()).isTrue();
    assertThat(redeemed(voucherId)).as("the refund gives the declaration back").isZero();

    ResponseEntity<String> victim = redeem(voucherId, 10, voucherProofs(blob, 8, 2));
    assertThat(victim.getStatusCode().is2xxSuccessful())
        .as("the victim's cap is restored, body=%s", victim.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  /** A replayed release, or one naming a different voucher, gives back nothing more. */
  @Test
  void aReleaseIsIdempotentAndBoundToItsOwnSwap() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);
    String first = receipt(declared(voucherId, 4, voucherProofs(blob, 4)));
    String second = receipt(declared(voucherId, 4, voucherProofs(blob, 4)));
    assertThat(redeemed(voucherId)).isEqualTo(8L);

    assertThat(release(voucherId, first, REDEEMER).getStatusCode().is2xxSuccessful()).isTrue();
    ResponseEntity<String> replay = release(voucherId, first, REDEEMER);

    assertThat(replay.getStatusCode().is2xxSuccessful()).as("idempotent, body=%s", replay.getBody()).isTrue();
    assertThat(redeemed(voucherId))
        .as("the replay releases nothing: the other swap's 4 sats stand").isEqualTo(4L);

    ResponseEntity<String> wrongVoucher =
        release(UUID.randomUUID().toString(), second, REDEEMER);
    assertThat(wrongVoucher.getStatusCode().is4xxClientError()).as("body=%s", wrongVoucher.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(4L);
  }

  /**
   * A release can never take back more than the swap it names declared, even when the redeemer
   * asks for more, or names a swap that declared nothing.
   */
  @Test
  void aReleaseCannotExceedItsDeclaration() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);
    String small = receipt(declared(voucherId, 2, voucherProofs(blob, 2)));
    receipt(declared(voucherId, 8, voucherProofs(blob, 8)));

    ResponseEntity<String> overAsk = post(RELEASE_PATH,
        MAPPER.writeValueAsString(Map.of("voucher_id", voucherId, "request_fingerprint",
            small, "sats", 10)), tokenHeaders(REDEEMER));
    assertThat(overAsk.getStatusCode().is4xxClientError())
        .as("asking for more than the swap declared is refused, body=%s", overAsk.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(10L);

    ResponseEntity<String> undeclared = release(voucherId, "ab".repeat(32), REDEEMER);
    assertThat(undeclared.getStatusCode().value())
        .as("a swap that declared nothing has nothing to release, body=%s", undeclared.getBody())
        .isEqualTo(404);

    assertThat(release(voucherId, small, REDEEMER).getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(redeemed(voucherId)).as("exactly the small swap's 2 sats").isEqualTo(8L);
  }

  /** Only a trusted redeemer releases, or anyone could refill a coupon and spend it again. */
  @Test
  void aReleaseNeedsATrustedRedeemerToken() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = receipt(declared(voucherId, 10, voucherProofs(signedBlobHex(voucherId), 8, 2)));

    assertThat(release(voucherId, body, null).getStatusCode().value()).isEqualTo(401);
    assertThat(release(voucherId, body, "guess").getStatusCode().value()).isEqualTo(401);
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  /**
   * Another service's token cannot release a swap it did not declare: the declaration records which
   * token declared it, so a leaked or misused token reaches only its own service's credits.
   */
  @Test
  void aReleaseIsHonouredOnlyForTheServiceThatDeclared() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = receipt(declared(voucherId, 10, voucherProofs(signedBlobHex(voucherId), 8, 2)));

    ResponseEntity<String> other = release(voucherId, body, "other-service-token");
    assertThat(other.getStatusCode().value()).as("body=%s", other.getBody()).isEqualTo(403);
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  // ----------------------------------------------------------------------------------- helpers

  ResponseEntity<String> release(String voucherId, String fingerprint, String token) throws Exception {
    return post(RELEASE_PATH,
        MAPPER.writeValueAsString(Map.of("voucher_id", voucherId, "request_fingerprint", fingerprint)),
        tokenHeaders(token));
  }

  static HttpHeaders tokenHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.add("X-Cashu-Voucher-Redeemer", token);
    }
    return headers;
  }

  /** A declared redemption that must succeed. */
  ResponseEntity<String> declared(String voucherId, long sats, List<Map<String, Object>> inputs)
      throws Exception {
    ResponseEntity<String> response = redeem(voucherId, sats, inputs);
    assertThat(response.getStatusCode().is2xxSuccessful()).as("body=%s", response.getBody()).isTrue();
    return response;
  }

  /** The receipt a declared swap answers with: the swap's NUT-19 request fingerprint. */
  static String receipt(ResponseEntity<String> declaredSwap) {
    String receipt = declaredSwap.getHeaders().getFirst(RECEIPT_HEADER);
    assertThat(receipt).as("a declared swap answers with its receipt").matches("[0-9a-f]{64}");
    return receipt;
  }
}
