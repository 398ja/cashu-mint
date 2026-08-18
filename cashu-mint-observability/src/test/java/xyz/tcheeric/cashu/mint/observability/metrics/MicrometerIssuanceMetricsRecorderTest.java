package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.metrics.IssuanceMetricsRecorder;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the issuance recorder port to the mandated Micrometer counter names. */
class MicrometerIssuanceMetricsRecorderTest {

    /** Every family must exist before its first failure, or dashboards read "no data". */
    @Test
    void registersEveryCounterEagerlyAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerIssuanceMetricsRecorder(registry);

        for (String name : new String[]{
                "cashu_mint_issuance_quote_expired_total",
                "cashu_mint_issuance_amount_mismatch_total",
                "cashu_mint_issuance_cross_check_failure_total",
                "cashu_mint_issuance_idempotent_replay_total",
                "cashu_mint_issuance_rate_limit_breach_total"}) {
            assertThat(registry.find(name).counter()).as(name).isNotNull();
            assertThat(registry.get(name).counter().count()).as(name).isZero();
        }
    }

    /** Each port method increments its own counter and no other. */
    @Test
    void eachMethodIncrementsItsOwnCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IssuanceMetricsRecorder recorder = new MicrometerIssuanceMetricsRecorder(registry);

        recorder.quoteExpired();
        recorder.amountMismatch();
        recorder.amountMismatch();
        recorder.crossCheckFailure();
        recorder.idempotentReplay();
        recorder.rateLimitBreach();

        assertThat(registry.get("cashu_mint_issuance_quote_expired_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cashu_mint_issuance_amount_mismatch_total").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("cashu_mint_issuance_cross_check_failure_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cashu_mint_issuance_idempotent_replay_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cashu_mint_issuance_rate_limit_breach_total").counter().count()).isEqualTo(1.0);
    }

    /** The legacy path label was a second encoding of the area and must not come back. */
    @Test
    void countersCarryNoPathLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerIssuanceMetricsRecorder(registry).amountMismatch();

        assertThat(registry.get("cashu_mint_issuance_amount_mismatch_total").counter().getId().getTag("path"))
                .isNull();
    }
}
