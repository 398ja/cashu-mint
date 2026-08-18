package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Registry of the typed metric recorder ports, one accessor per domain area —
 * see {@code docs/adr/0001-typed-metric-recorders.md}.
 *
 * <p>This is the replacement for {@code MintIntegrityContext.meterRegistry()}:
 * domain code asks for its area's recorder instead of for a raw
 * {@code MeterRegistry}, so no call site can invent a metric name. Accessors
 * never return {@code null} — an unwired area records into a no-op, which is
 * what unit-test contexts and observability-disabled deploys get. Call sites
 * therefore carry no null checks.
 *
 * <p>The observability module registers the Micrometer implementations at
 * Spring bootstrap, mirroring {@link TaskExecutionRecorder} and
 * {@link LockMetricsRecorder}.
 */
public final class MetricRecorders {

    private static final MeltMetricsRecorder NO_OP_MELT = new MeltMetricsRecorder() {
        @Override
        public void insufficientInput() {
        }

        @Override
        public void proofsNotBound() {
        }
    };

    private static final VoucherMetricsRecorder NO_OP_VOUCHER = new VoucherMetricsRecorder() {
        @Override
        public void rejected(VoucherRejectionReason reason) {
        }

        @Override
        public void issued(xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource fundingSource) {
        }

        @Override
        public void iouIssuanceAttempted() {
        }

        @Override
        public void lazyFundingCreated() {
        }

        @Override
        public void rateLimitBreach() {
        }
    };

    private static volatile MeltMetricsRecorder melt = NO_OP_MELT;

    private static volatile VoucherMetricsRecorder voucher = NO_OP_VOUCHER;

    private MetricRecorders() {
    }

    /** The melt (NUT-05) area recorder. Never {@code null}. */
    public static MeltMetricsRecorder melt() {
        return melt;
    }

    /**
     * Registers the melt recorder. Passing {@code null} resets to the no-op.
     *
     * @param recorder the recorder to use, or {@code null} to disable reporting
     */
    public static void registerMelt(MeltMetricsRecorder recorder) {
        melt = recorder != null ? recorder : NO_OP_MELT;
    }

    /**
     * The voucher area recorder. Never {@code null}.
     *
     * @return the registered recorder, or a no-op when none is wired
     */
    public static VoucherMetricsRecorder voucher() {
        return voucher;
    }

    /**
     * Registers the voucher recorder. Passing {@code null} resets to the no-op.
     *
     * @param recorder the recorder to use, or {@code null} to disable reporting
     */
    public static void registerVoucher(VoucherMetricsRecorder recorder) {
        voucher = recorder != null ? recorder : NO_OP_VOUCHER;
    }
}
