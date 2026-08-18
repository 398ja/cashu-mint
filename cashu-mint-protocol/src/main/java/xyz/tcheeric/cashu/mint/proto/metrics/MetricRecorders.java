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

    private static final IssuanceMetricsRecorder NO_OP_ISSUANCE = new IssuanceMetricsRecorder() {
        @Override
        public void quoteExpired() {
        }

        @Override
        public void amountMismatch() {
        }

        @Override
        public void crossCheckFailure() {
        }

        @Override
        public void idempotentReplay() {
        }

        @Override
        public void rateLimitBreach() {
        }
    };

    private static final WebhookMetricsRecorder NO_OP_WEBHOOK =
            outcome -> { };

    private static final InvariantMetricsRecorder NO_OP_INVARIANT = new InvariantMetricsRecorder() {
        @Override
        public void bindStuckPaymentUnknown(java.util.function.Supplier<Number> value) {
        }

        @Override
        public void pollFailed() {
        }
    };

    private static volatile MeltMetricsRecorder melt = NO_OP_MELT;

    private static volatile VoucherMetricsRecorder voucher = NO_OP_VOUCHER;

    private static volatile IssuanceMetricsRecorder issuance = NO_OP_ISSUANCE;

    private static volatile WebhookMetricsRecorder webhook = NO_OP_WEBHOOK;

    private static volatile InvariantMetricsRecorder invariant = NO_OP_INVARIANT;

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

    /**
     * The mint/issuance area recorder. Never {@code null}.
     *
     * @return the registered recorder, or a no-op when none is wired
     */
    public static IssuanceMetricsRecorder issuance() {
        return issuance;
    }

    /**
     * Registers the issuance recorder. Passing {@code null} resets to the no-op.
     *
     * @param recorder the recorder to use, or {@code null} to disable reporting
     */
    public static void registerIssuance(IssuanceMetricsRecorder recorder) {
        issuance = recorder != null ? recorder : NO_OP_ISSUANCE;
    }

    /**
     * The webhook area recorder. Never {@code null}.
     *
     * @return the registered recorder, or a no-op when none is wired
     */
    public static WebhookMetricsRecorder webhook() {
        return webhook;
    }

    /**
     * Registers the webhook recorder. Passing {@code null} resets to the no-op.
     *
     * @param recorder the recorder to use, or {@code null} to disable reporting
     */
    public static void registerWebhook(WebhookMetricsRecorder recorder) {
        webhook = recorder != null ? recorder : NO_OP_WEBHOOK;
    }

    /**
     * The operational-invariant gauge recorder. Never {@code null}.
     *
     * @return the registered recorder, or a no-op when none is wired
     */
    public static InvariantMetricsRecorder invariant() {
        return invariant;
    }

    /**
     * Registers the invariant recorder. Passing {@code null} resets to the no-op.
     *
     * @param recorder the recorder to use, or {@code null} to disable reporting
     */
    public static void registerInvariant(InvariantMetricsRecorder recorder) {
        invariant = recorder != null ? recorder : NO_OP_INVARIANT;
    }
}
