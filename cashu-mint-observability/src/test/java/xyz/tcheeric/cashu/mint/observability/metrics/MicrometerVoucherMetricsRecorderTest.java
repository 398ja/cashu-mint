package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the voucher recorder port to the mandated counter names and labels. */
class MicrometerVoucherMetricsRecorderTest {

    /** One pre-registered series per reason and per funding source. */
    @Test
    void registersEverySeriesEagerly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerVoucherMetricsRecorder(registry);

        for (VoucherRejectionReason reason : VoucherRejectionReason.values()) {
            assertThat(registry.find("cashu_mint_voucher_rejected_total")
                    .tag("reason", reason.label()).counter()).as(reason.name()).isNotNull();
        }
        for (VoucherFundingSource source : VoucherFundingSource.values()) {
            assertThat(registry.find("cashu_mint_voucher_issued_total")
                    .tag("funding_source", source.name()).counter()).as(source.name()).isNotNull();
        }
        assertThat(registry.find("cashu_mint_voucher_iou_issued_total").counter()).isNotNull();
        assertThat(registry.find("cashu_mint_voucher_lazy_funding_total").counter()).isNotNull();
        assertThat(registry.find("cashu_mint_voucher_rate_limit_breach_total").counter()).isNotNull();
    }

    /** Rejections separate by reason rather than by metric name. */
    @Test
    void rejectionsSeparateByReasonLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VoucherMetricsRecorder recorder = new MicrometerVoucherMetricsRecorder(registry);

        recorder.rejected(VoucherRejectionReason.FUNDING_REQUIRED);
        recorder.rejected(VoucherRejectionReason.FUNDING_REQUIRED);
        recorder.rejected(VoucherRejectionReason.IOU_NOT_PERMITTED);

        assertThat(registry.get("cashu_mint_voucher_rejected_total")
                .tag("reason", "funding_required").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("cashu_mint_voucher_rejected_total")
                .tag("reason", "iou_not_permitted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cashu_mint_voucher_rejected_total")
                .tag("reason", "face_value_not_backed").counter().count()).isZero();
    }

    /** The breach counter must never grow a principal dimension. */
    @Test
    void rateLimitBreachCarriesNoPrincipalLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerVoucherMetricsRecorder(registry).rateLimitBreach();

        assertThat(registry.get("cashu_mint_voucher_rate_limit_breach_total")
                .counter().getId().getTag("principal")).isNull();
    }

    /** Untrusted voucher signers count per binding mode, with no issuer or key label (#527). */
    @Test
    void untrustedSignersCountByModeOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VoucherMetricsRecorder recorder = new MicrometerVoucherMetricsRecorder(registry);

        recorder.issuerUntrusted(VoucherIssuerBindingMode.LOG);
        recorder.issuerUntrusted(VoucherIssuerBindingMode.LOG);
        recorder.issuerUntrusted(VoucherIssuerBindingMode.ENFORCE);
        recorder.issuerUntrusted(VoucherIssuerBindingMode.OFF);

        assertThat(registry.get("cashu_mint_voucher_issuer_untrusted_total")
                .tag("mode", "log").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("cashu_mint_voucher_issuer_untrusted_total")
                .tag("mode", "enforce").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("cashu_mint_voucher_issuer_untrusted_total")
                .tag("mode", "off").counter()).isNull();
        assertThat(registry.get("cashu_mint_voucher_issuer_untrusted_total")
                .tag("mode", "log").counter().getId().getTags()).hasSize(1);
    }

    /** Unsigned voucher proofs count per binding mode, separately from untrusted signers (#527). */
    @Test
    void unsignedVouchersCountByModeOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VoucherMetricsRecorder recorder = new MicrometerVoucherMetricsRecorder(registry);

        recorder.unsignedVoucher(VoucherIssuerBindingMode.LOG);
        recorder.unsignedVoucher(VoucherIssuerBindingMode.ENFORCE);
        recorder.unsignedVoucher(VoucherIssuerBindingMode.OFF);

        assertThat(registry.get("cashu_mint_voucher_unsigned_total")
                .tag("mode", "log").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cashu_mint_voucher_unsigned_total")
                .tag("mode", "enforce").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("cashu_mint_voucher_unsigned_total")
                .tag("mode", "off").counter()).isNull();
        assertThat(registry.get("cashu_mint_voucher_issuer_untrusted_total")
                .tag("mode", "log").counter().count()).isZero();
    }
}
