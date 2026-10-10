package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * cashu-mint#540 review §1.2d: in {@code off} the declaration headers are not even parsed, so a
 * malformed one cannot refuse a swap on a mint that has the cap switched off.
 */
@TestPropertySource(properties = {
    "cashu.mint.voucher.redemption-cap=off",
    "cashu.mint.voucher.redeemer-tokens=" + VoucherRedemptionCapOffModeIT.REDEEMER,
})
class VoucherRedemptionCapOffModeIT extends VoucherRedemptionFixtures {

  static final String REDEEMER = "it-off-redeemer-token";

  @Override
  String redeemerToken() {
    return REDEEMER;
  }

  @Test
  void aMalformedDeclarationIsNotParsedInOffMode() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    ResponseEntity<String> malformed = swap(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2),
        "not a declaration at all", "guess");

    assertThat(malformed.getStatusCode().is2xxSuccessful()).as("body=%s", malformed.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isZero();
  }
}
