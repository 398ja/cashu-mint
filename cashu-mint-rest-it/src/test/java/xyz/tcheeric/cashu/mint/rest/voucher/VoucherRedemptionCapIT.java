package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * cashu-mint#540: copied voucher metadata on plain sats must not let one coupon be credited
 * without limit.
 *
 * <p>Drives the real {@code POST /v1/swap} against Postgres with a real, gateway-signed unlocked
 * voucher blob, the shape gateway-customer issues. The port of imani-gateway-core's
 * {@code R3ReviewForgeryPocTest.c1}: one EUR 5.00 MINIMAL coupon (10 sats backing, ratio 50
 * cents/sat) whose blob the attacker copies onto fresh proofs. Every copy has a fresh nonce, so a
 * fresh {@code Y}, and passes the signature, the issuer binding and the double-spend check.
 *
 * <p>The redeemer (gateway-core, gateway-customer) credits a coupon on the swap that takes it and
 * declares that credit in {@code X-Cashu-Voucher-Redemption}. The mint holds the declared credits
 * of one coupon to the sats issued for it.
 */
@TestPropertySource(properties = {
    "cashu.mint.voucher.redemption-cap=enforce",
    "cashu.mint.voucher.issuer-binding=enforce",
    "cashu.mint.voucher.trusted-signers=" + VoucherRedemptionFixtures.ISSUER_PUB_X,
    "cashu.mint.voucher.redeemer-tokens=" + VoucherRedemptionCapIT.REDEEMER_TOKEN + ",other-service-token",
})
class VoucherRedemptionCapIT extends VoucherRedemptionFixtures {

  static final String REDEEMER_TOKEN = "it-core-redeemer-token";

  @Override
  String redeemerToken() {
    return REDEEMER_TOKEN;
  }

  // ------------------------------------------------------------------------------------ attack

  /**
   * R3ReviewForgeryPocTest.c1 at the mint. The honest holder redeems the coupon once, for its 10
   * sats. The attacker then relabels the same blob onto 10 fresh plain sats three times, and each
   * time a redeemer credits it. The first relabel takes the coupon past its issued sats and every
   * one is refused, with nothing signed.
   */
  @Test
  void relabellingASpentCouponsBlobIsRefusedOnceItsIssuedSatsAreRedeemed() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    ResponseEntity<String> honest = redeem(voucherId, 10, voucherProofs(blob, 8, 2));
    assertThat(honest.getStatusCode().is2xxSuccessful()).as("body=%s", honest.getBody()).isTrue();

    for (int round = 0; round < 3; round++) {
      long signedBefore = blindSignatureJpaRepository.count();
      ResponseEntity<String> relabel = redeem(voucherId, 10, voucherProofs(blob, 8, 2));
      assertThat(relabel.getStatusCode().is4xxClientError())
          .as("round %d: a relabelled copy of a fully redeemed coupon must be refused, body=%s",
              round, relabel.getBody())
          .isTrue();
      assertThat(relabel.getBody()).contains("\"code\":90025");
      assertThat(blindSignatureJpaRepository.count())
          .as("a refused redemption signs nothing").isEqualTo(signedBefore);
    }
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  /** Partial credits of one coupon add up to its issued sats, and one sat more is refused. */
  @Test
  void partialRedemptionsAddUpToTheIssuedSatsAndNoFurther() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    assertThat(redeem(voucherId, 4, voucherProofs(blob, 4)).getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(redeem(voucherId, 4, voucherProofs(blob, 4)).getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(redeem(voucherId, 2, voucherProofs(blob, 2)).getStatusCode().is2xxSuccessful()).isTrue();
    ResponseEntity<String> oneMore = redeem(voucherId, 1, voucherProofs(blob, 1));

    assertThat(oneMore.getStatusCode().is4xxClientError()).as("body=%s", oneMore.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  // ------------------------------------------------------------------------------- honest flows

  /**
   * An honest coupon changes hands many times before it is spent: every hop is a swap of V into
   * V. None of them is a credit, so none is declared and none is counted, and the final
   * redemption of the full coupon is accepted.
   */
  @Test
  void anHonestLongSplitChainIsUnaffected() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    for (int hop = 0; hop < 30; hop++) {
      ResponseEntity<String> split = swap(voucherProofs(blob, 8, 2), outputs(4, 4, 1, 1), null, null);
      assertThat(split.getStatusCode().is2xxSuccessful())
          .as("hop %d: an undeclared voucher split is an ordinary swap, body=%s", hop, split.getBody())
          .isTrue();
    }
    ResponseEntity<String> redemption = redeem(voucherId, 10, voucherProofs(blob, 8, 2));

    assertThat(redemption.getStatusCode().is2xxSuccessful()).as("body=%s", redemption.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  /** A plain-sat swap is never touched by any of this. */
  @Test
  void aPlainSwapIsUnaffected() throws Exception {
    ResponseEntity<String> plain = swap(plainProofs(8), outputs(8), null, null);
    assertThat(plain.getStatusCode().is2xxSuccessful()).as("body=%s", plain.getBody()).isTrue();
  }

  // ---------------------------------------------------------------------- melt and burn paths

  /**
   * A voucher cannot leave the voucher system through melt: Model B refuses every voucher secret
   * there, so melt needs no accounting. Pinned so a change that lets vouchers melt has to account
   * for them. (A merchant "burn" melts the merchant's PLAIN sats, never the coupon, and is an
   * ordinary melt.)
   */
  @Test
  void aVoucherCannotBeMeltedSoMeltNeedsNoAccounting() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    List<Map<String, Object>> proofs = voucherProofs(signedBlobHex(voucherId), 8, 2);
    String body = MAPPER.writeValueAsString(Map.of("quote", "q-" + UUID.randomUUID(), "inputs", proofs));

    ResponseEntity<String> melt = post("/v1/melt/bolt11", body, new HttpHeaders());

    assertThat(melt.getStatusCode().is2xxSuccessful()).as("body=%s", melt.getBody()).isFalse();
    assertThat(redeemed(voucherId)).isZero();
  }

  // ------------------------------------------------------------------------------- concurrency

  /**
   * Twelve redeemers credit different proofs of one coupon at once, 5 sats each. The coupon's 10
   * sats admit exactly two, whatever the interleaving.
   */
  @Test
  void concurrentRedemptionsOfOneCouponAreAtomic() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);
    int redeemers = 12;
    List<List<Map<String, Object>>> inputs = new ArrayList<>();
    for (int i = 0; i < redeemers; i++) {
      inputs.add(voucherProofs(blob, 4, 1));
    }
    ExecutorService pool = Executors.newFixedThreadPool(redeemers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<ResponseEntity<String>>> results = new ArrayList<>();
      for (List<Map<String, Object>> in : inputs) {
        Callable<ResponseEntity<String>> call = () -> {
          start.await();
          return redeem(voucherId, 5, in);
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      int accepted = 0;
      for (Future<ResponseEntity<String>> r : results) {
        if (r.get().getStatusCode().is2xxSuccessful()) {
          accepted++;
        }
      }
      assertThat(accepted).as("10 sats admit exactly two 5-sat credits").isEqualTo(2);
      assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
    } finally {
      pool.shutdownNow();
    }
  }

  // ----------------------------------------------------------------------- the declaration itself

  /** Without a trusted redeemer token a declaration could exhaust someone else's coupon. */
  @Test
  void aDeclarationWithoutATrustedRedeemerTokenIsRefused() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    ResponseEntity<String> noToken = swap(voucherProofs(blob, 8, 2), outputs(8, 2), voucherId + ":10", null);
    ResponseEntity<String> wrongToken =
        swap(voucherProofs(blob, 8, 2), outputs(8, 2), voucherId + ":10", "guess");

    assertThat(noToken.getStatusCode().value()).as("body=%s", noToken.getBody()).isEqualTo(401);
    assertThat(wrongToken.getStatusCode().value()).isEqualTo(401);
    assertThat(redeemed(voucherId)).isZero();
  }

  /** A declaration naming another coupon than its inputs, or more sats than they carry, is refused. */
  @Test
  void aDeclarationThatDoesNotMatchItsInputsIsRefused() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    ResponseEntity<String> otherVoucher =
        redeem(UUID.randomUUID().toString(), 10, voucherProofs(blob, 8, 2));
    ResponseEntity<String> tooMany = redeem(voucherId, 11, voucherProofs(blob, 8, 2));
    ResponseEntity<String> exactlyOneMore = redeem(voucherId, 5, voucherProofs(blob, 4));
    ResponseEntity<String> plainInputs = redeem(voucherId, 8, plainProofs(8));

    assertThat(otherVoucher.getStatusCode().is4xxClientError()).isTrue();
    assertThat(tooMany.getStatusCode().is4xxClientError()).isTrue();
    assertThat(exactlyOneMore.getStatusCode().is4xxClientError())
        .as("one sat more than the inputs carry is refused, body=%s", exactlyOneMore.getBody()).isTrue();
    assertThat(plainInputs.getStatusCode().is4xxClientError()).isTrue();
    assertThat(redeemed(voucherId)).isZero();
  }

  /**
   * A redemption whose swap is refused for another reason after the claim (here: its outputs were
   * already signed) gives its claim back, so the coupon can still be redeemed.
   */
  @Test
  void aSwapRefusedAfterItsClaimGivesTheClaimBack() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);
    List<Map<String, Object>> reused = outputs(8, 2);
    assertThat(swap(plainProofs(8, 2), reused, null, null).getStatusCode().is2xxSuccessful()).isTrue();

    ResponseEntity<String> refused = swap(voucherProofs(blob, 8, 2), reused, voucherId + ":10", REDEEMER_TOKEN);
    assertThat(refused.getStatusCode().is4xxClientError()).as("body=%s", refused.getBody()).isTrue();
    assertThat(redeemed(voucherId)).as("the refused swap's claim is given back").isZero();

    assertThat(redeem(voucherId, 10, voucherProofs(blob, 8, 2)).getStatusCode().is2xxSuccessful()).isTrue();
  }

  /** In enforce, and only in enforce, a malformed declaration refuses the swap. */
  @Test
  void aMalformedDeclarationIsRefusedInEnforce() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    ResponseEntity<String> malformed = swap(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2),
        voucherId + ":ten", REDEEMER_TOKEN);
    assertThat(malformed.getStatusCode().is4xxClientError()).as("body=%s", malformed.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isZero();
  }

  /**
   * Review §1.2b: the cap is fixed by the coupon's signed terms. A coupon first seen with no stated
   * cap (NULL) takes the real cap the first time a declaration states it, rather than staying
   * uncapped for ever; and in enforce a declaration whose terms derive a different cap than the one
   * on record is refused, so a mismatched blob can neither lock in nor use a wrong value.
   */
  @Test
  void anUnknownCapIsUpgradedAndAMismatchedCapIsRefused() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("INSERT INTO voucher_redemption (voucher_id, cap_sats, redeemed_sats) VALUES (?, NULL, 0)",
        voucherId);

    assertThat(redeem(voucherId, 10, voucherProofs(signedBlobHex(voucherId), 8, 2)).getStatusCode()
        .is2xxSuccessful()).isTrue();
    assertThat(jdbc.queryForObject("SELECT cap_sats FROM voucher_redemption WHERE voucher_id = ?",
        Long.class, voucherId)).as("NULL upgraded to the signed cap").isEqualTo(CAP_SATS);
    ResponseEntity<String> over = redeem(voucherId, 1, voucherProofs(signedBlobHex(voucherId), 1));
    assertThat(over.getStatusCode().is4xxClientError()).as("now capped, body=%s", over.getBody()).isTrue();

    String other = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO voucher_redemption (voucher_id, cap_sats, redeemed_sats) VALUES (?, 500, 0)", other);
    ResponseEntity<String> mismatched = redeem(other, 2, voucherProofs(signedBlobHex(other), 2));
    assertThat(mismatched.getStatusCode().is4xxClientError())
        .as("terms deriving 10 against a recorded 500 are refused, body=%s", mismatched.getBody()).isTrue();
    assertThat(redeemed(other)).isZero();
  }

  /** A NUT-19 replay of a redemption is answered from the cache and not counted twice. */
  @Test
  void aReplayedRedemptionIsNotCountedTwice() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = swapBody(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2));
    HttpHeaders headers = redemptionHeaders(voucherId + ":10", REDEEMER_TOKEN);

    ResponseEntity<String> original = post("/v1/swap", body, headers);
    ResponseEntity<String> replay = post("/v1/swap", body, headers);

    assertThat(original.getStatusCode().is2xxSuccessful()).as("body=%s", original.getBody()).isTrue();
    assertThat(replay.getStatusCode().is2xxSuccessful()).as("body=%s", replay.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

}
