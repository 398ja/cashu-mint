package xyz.tcheeric.cashu.mint.jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #459 — how the sweep rescues a voucher the customer paid for and never
 * received.
 *
 * <p>The direction is the point, and it is the opposite of the melt sweep:
 * the money has already been taken, so the sweep completes the obligation
 * rather than abandoning it.
 */
class VoucherFundingReconcilerTest {

    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final int BATCH = 200;

    private VoucherQuoteJpaRepository voucherQuotes;
    private VoucherFundingResolver fundingResolver;
    private VoucherFundingReconciler reconciler;
    private final List<Boolean> reconcileOutcomes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        voucherQuotes = Mockito.mock(VoucherQuoteJpaRepository.class);
        fundingResolver = Mockito.mock(VoucherFundingResolver.class);
        reconciler = new VoucherFundingReconciler(voucherQuotes, fundingResolver, GRACE, BATCH);
        reconcileOutcomes.clear();
        MetricRecorders.registerVoucher(new RecordingVoucherRecorder(reconcileOutcomes));
    }

    @AfterEach
    void resetGlobalRecorder() {
        // MetricRecorders is a JVM-global; leaving this class's recorder
        // installed would have every later test in the JVM record into it.
        MetricRecorders.registerVoucher(null);
    }

    private static VoucherQuoteEntity quote(String quoteId) {
        VoucherQuoteEntity entity = new VoucherQuoteEntity();
        entity.setQuoteId(quoteId);
        entity.setVoucherType("customer_paid");
        entity.setFaceValue(20_000L);
        entity.setChargedAmount(50L);
        entity.setUnit("sat");
        entity.setLifecycleState(VoucherLifecycleState.UNFUNDED);
        return entity;
    }

    private static VoucherFunding funding(String fundingId) {
        return new FundingStub(fundingId);
    }

    private void sweepFinding(VoucherQuoteEntity... stranded) {
        when(voucherQuotes.findPaidButUnfunded(any(), anyInt())).thenReturn(List.of(stranded));
        reconciler.reconcileTick();
    }

    /**
     * The case this exists for: the customer paid, the client never came back,
     * and nothing else in the system would ever have funded the quote.
     */
    @Test
    void shouldFundAQuoteThatWasPaidForButNeverFunded() {
        // Arrange
        VoucherQuoteEntity stranded = quote("v-stranded");
        when(fundingResolver.resolveForQuote(stranded)).thenReturn(Optional.of(funding("f-1")));
        when(voucherQuotes.attachFundingAndAdvance("v-stranded", "f-1")).thenReturn(1);

        // Act
        sweepFinding(stranded);

        // Assert
        verify(voucherQuotes).attachFundingAndAdvance("v-stranded", "f-1");
        assertThat(reconcileOutcomes).containsExactly(true);
    }

    /**
     * All sixty-eight stranded rows heal on one sweep rather than one per tick,
     * which is what makes the existing backlog recoverable without an operator.
     */
    @Test
    void shouldFundEveryStrandedQuoteInOneSweep() {
        // Arrange
        VoucherQuoteEntity[] stranded = new VoucherQuoteEntity[5];
        for (int i = 0; i < stranded.length; i++) {
            stranded[i] = quote("v-" + i);
            when(fundingResolver.resolveForQuote(stranded[i]))
                    .thenReturn(Optional.of(funding("f-" + i)));
            when(voucherQuotes.attachFundingAndAdvance("v-" + i, "f-" + i)).thenReturn(1);
        }

        // Act
        sweepFinding(stranded);

        // Assert
        for (int i = 0; i < stranded.length; i++) {
            verify(voucherQuotes).attachFundingAndAdvance("v-" + i, "f-" + i);
        }
    }

    /**
     * A quote paid moments ago is mid-flight, not stranded: the webhook path is
     * still attaching its funding. The sweep must ask only for payments older
     * than the grace period so it does not race half A.
     */
    @Test
    void shouldOnlyConsiderPaymentsOlderThanTheGracePeriod() {
        // Arrange
        Instant boundary = Instant.now().minus(GRACE);
        when(voucherQuotes.findPaidButUnfunded(any(), anyInt())).thenReturn(List.of());

        // Act
        reconciler.reconcileTick();

        // Assert
        verify(voucherQuotes).findPaidButUnfunded(
                Mockito.argThat(cutoff -> !cutoff.isBefore(boundary.minusSeconds(5))
                        && !cutoff.isAfter(Instant.now())),
                Mockito.eq(BATCH));
    }

    /**
     * A concurrent webhook or client request winning the CAS is the mechanism
     * working, not a failure: the quote is funded either way and must not be
     * funded twice.
     */
    @Test
    void shouldTreatALostCasAsAlreadyHandled() {
        // Arrange
        VoucherQuoteEntity stranded = quote("v-race");
        when(fundingResolver.resolveForQuote(stranded)).thenReturn(Optional.of(funding("f-race")));
        when(voucherQuotes.attachFundingAndAdvance("v-race", "f-race")).thenReturn(0);

        // Act
        sweepFinding(stranded);

        // Assert — not counted as recovered; nothing was recovered.
        assertThat(reconcileOutcomes).isEmpty();
    }

    /**
     * Money the mint cannot account for must stay visible rather than be
     * guessed at, so an unresolvable quote is left alone and counted as failed.
     */
    @Test
    void shouldLeaveAQuoteAloneWhenItsFundingCannotBeResolved() {
        // Arrange
        VoucherQuoteEntity stranded = quote("v-unresolvable");
        when(fundingResolver.resolveForQuote(stranded)).thenReturn(Optional.empty());

        // Act
        sweepFinding(stranded);

        // Assert
        verify(voucherQuotes, never()).attachFundingAndAdvance(anyString(), anyString());
        assertThat(reconcileOutcomes).containsExactly(false);
    }

    /**
     * One unrecoverable quote must not stop the others being swept, or a single
     * bad row would strand every later customer indefinitely.
     */
    @Test
    void shouldKeepSweepingAfterOneQuoteFails() {
        // Arrange
        VoucherQuoteEntity failing = quote("v-failing");
        VoucherQuoteEntity healthy = quote("v-healthy");
        when(fundingResolver.resolveForQuote(failing))
                .thenThrow(new RuntimeException("funding store unreachable"));
        when(fundingResolver.resolveForQuote(healthy)).thenReturn(Optional.of(funding("f-ok")));
        when(voucherQuotes.attachFundingAndAdvance("v-healthy", "f-ok")).thenReturn(1);

        // Act
        sweepFinding(failing, healthy);

        // Assert
        verify(voucherQuotes).attachFundingAndAdvance("v-healthy", "f-ok");
    }

    /**
     * A failing query must not kill the schedule, because the next sweep is the
     * only thing that recovers the rows this one dropped.
     */
    @Test
    void shouldSurviveATickWhoseQueryFails() {
        // Arrange
        when(voucherQuotes.findPaidButUnfunded(any(), anyInt()))
                .thenThrow(new RuntimeException("database unreachable"));

        // Act — must not propagate.
        reconciler.reconcileTick();

        // Assert
        verify(voucherQuotes, never()).attachFundingAndAdvance(anyString(), anyString());
    }

    /**
     * Without the JPA module wired the sweep does nothing at all, so a mint
     * booted without it keeps working rather than failing on every tick.
     */
    @Test
    void shouldDoNothingWhenTheDependenciesAreNotWired() {
        // Arrange
        VoucherFundingReconciler unwired = new VoucherFundingReconciler(null, null, GRACE, BATCH);

        // Act
        unwired.reconcileTick();

        // Assert
        verify(voucherQuotes, never()).findPaidButUnfunded(any(), anyInt());
    }

    /**
     * A misconfigured batch size of zero would make the sweep a silent no-op —
     * the one failure mode a safety net must not have, because it looks exactly
     * like a healthy system with nothing to do.
     */
    @Test
    void shouldRefuseANonPositiveBatchSizeRatherThanSweepNothing() {
        // Arrange
        VoucherFundingReconciler misconfigured =
                new VoucherFundingReconciler(voucherQuotes, fundingResolver, GRACE, 0);
        when(voucherQuotes.findPaidButUnfunded(any(), anyInt())).thenReturn(List.of());

        // Act
        misconfigured.reconcileTick();

        // Assert — swept with a usable batch size, not 0.
        verify(voucherQuotes).findPaidButUnfunded(any(), Mockito.intThat(size -> size > 0));
    }

    /**
     * The sweep takes the oldest rows first and bounds the batch, so rows that
     * can never resolve hold the front of every tick and starve newer ones.
     * A full batch is the only signal that distinguishes that from ordinary
     * progress, since the gauge is non-zero in both cases.
     */
    @Test
    void shouldStillSweepEveryRowWhenTheBatchComesBackFull() {
        // Arrange — exactly BATCH rows, i.e. there may be more waiting.
        VoucherQuoteEntity[] full = new VoucherQuoteEntity[BATCH];
        for (int i = 0; i < BATCH; i++) {
            full[i] = quote("v-full-" + i);
            when(fundingResolver.resolveForQuote(full[i]))
                    .thenReturn(Optional.of(funding("f-full-" + i)));
            when(voucherQuotes.attachFundingAndAdvance("v-full-" + i, "f-full-" + i)).thenReturn(1);
        }

        // Act
        sweepFinding(full);

        // Assert — every row in the batch is attempted, none dropped.
        assertThat(reconcileOutcomes).hasSize(BATCH).containsOnly(true);
    }

    private record FundingStub(String fundingId) implements VoucherFunding {
        @Override public VoucherFundingSource fundingSource() {
            return VoucherFundingSource.CUSTOMER_PAYMENT;
        }
        @Override public long amount() { return 50L; }
        @Override public String unit() { return "sat"; }
        @Override public Instant createdAt() { return Instant.now(); }
    }

    /** Records reconcile outcomes; the name mapping is asserted in the observability module. */
    private record RecordingVoucherRecorder(List<Boolean> outcomes) implements VoucherMetricsRecorder {
        @Override public void rejected(VoucherRejectionReason reason) { }
        @Override public void issued(VoucherFundingSource fundingSource) { }
        @Override public void iouIssuanceAttempted() { }
        @Override public void lazyFundingCreated() { }
        @Override public void fundingReconciled(boolean recovered) { outcomes.add(recovered); }
        @Override public void rateLimitBreach() { }
    }
}
