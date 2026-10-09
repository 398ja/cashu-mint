package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;

/**
 * cashu-mint#540 in its default mode, {@code log}: the relabelled coupon is still credited (no
 * behaviour change on rollout), but the overspend is recorded and counted under
 * {@code cashu_mint_voucher_overspend_refused_total{mode="log"}}, which the
 * {@code VoucherOverspendLogged} alert watches before anyone turns on {@code enforce}.
 */
@TestPropertySource(properties = {
    "cashu.observability.enabled=true",
    "cashu.mint.voucher.redemption-cap=log",
    "cashu.mint.voucher.redeemer-tokens=" + VoucherRedemptionCapLogModeIT.REDEEMER,
})
class VoucherRedemptionCapLogModeIT extends VoucherRedemptionFixtures {

  static final String REDEEMER = "it-log-redeemer-token";

  @Autowired MeterRegistry meterRegistry;
  @Autowired VoucherMetricsRecorder voucherMetricsRecorder;

  @BeforeEach
  void pinRecorder() {
    // MetricRecorders is a JVM-global, last-writer-wins static: pin it to this context's bean.
    MetricRecorders.registerVoucher(voucherMetricsRecorder);
  }

  @Override
  String redeemerToken() {
    return REDEEMER;
  }

  @Test
  void anOverspendIsAcceptedRecordedAndCountedInLogMode() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);
    double before = overspendCount("log");

    ResponseEntity<String> honest = redeem(voucherId, 10, voucherProofs(blob, 8, 2));
    ResponseEntity<String> relabel = redeem(voucherId, 10, voucherProofs(blob, 8, 2));

    assertThat(honest.getStatusCode().is2xxSuccessful()).as("body=%s", honest.getBody()).isTrue();
    assertThat(relabel.getStatusCode().is2xxSuccessful())
        .as("log mode changes no outcome, body=%s", relabel.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(2 * CAP_SATS);
    assertThat(overspent(voucherId)).isTrue();
    assertThat(overspendCount("log") - before).isEqualTo(1.0);
    assertThat(overspendCount("enforce")).isZero();
  }

  @Test
  void bothOverspendSeriesArePreRegistered() {
    assertThat(meterRegistry.find("cashu_mint_voucher_overspend_refused_total").tag("mode", "log").counter())
        .isNotNull();
    assertThat(meterRegistry.find("cashu_mint_voucher_overspend_refused_total").tag("mode", "enforce").counter())
        .isNotNull();
  }

  private double overspendCount(String mode) {
    var counter = meterRegistry.find("cashu_mint_voucher_overspend_refused_total").tag("mode", mode).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
