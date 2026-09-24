package xyz.tcheeric.cashu.mint.webhook;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEventRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 001 T203 — drives {@link QuoteStatusUpdater#record} through the
 * durable outcome matrix (FR-005 / FR-006 / FR-008) using mocked
 * {@link MintQuoteRepository} and {@link WebhookEventRepository}.
 */
class QuoteStatusUpdaterDurableTest {

    private static final String PROVIDER = "phoenixd";

    private QuoteStatusUpdater updater;
    private MintQuoteRepository mintQuoteRepository;
    private VoucherQuoteRepository voucherQuoteRepository;
    private WebhookEventRepository webhookEventRepository;
    private VoucherFundingResolver voucherFundingResolver;
    private WebhookProperties webhookProperties;
    private SimpleMeterRegistry meterRegistry;

    /**
     * Counting stand-in for the webhook recorder. The mapping from port
     * method to Micrometer name lives in
     * {@code MicrometerWebhookMetricsRecorderTest}; this module only cares
     * that the right outcome was recorded once.
     */
    private final java.util.List<Outcome> outcomes = new java.util.ArrayList<>();

    @AfterEach
    void resetGlobalRecorder() {
        // MetricRecorders is a JVM-global; leaving this class's list installed
        // would have every later test in the surefire JVM record into it.
        MetricRecorders.registerWebhook(null);
    }

    private long outcomeCount(Outcome outcome) {
        return outcomes.stream().filter(o -> o == outcome).count();
    }

    @BeforeEach
    void setUp() {
        mintQuoteRepository = Mockito.mock(MintQuoteRepository.class);
        voucherQuoteRepository = Mockito.mock(VoucherQuoteRepository.class);
        webhookEventRepository = Mockito.mock(WebhookEventRepository.class);
        voucherFundingResolver = Mockito.mock(VoucherFundingResolver.class);
        webhookProperties = new WebhookProperties();
        webhookProperties.setProvider(PROVIDER);
        meterRegistry = new SimpleMeterRegistry();
        outcomes.clear();
        MetricRecorders.registerWebhook(outcomes::add);

        updater = new QuoteStatusUpdater(
                Duration.ofHours(1), Duration.ofHours(24), 10_485_760L, 100_000,
                meterRegistry, mintQuoteRepository, voucherQuoteRepository,
                webhookEventRepository, voucherFundingResolver, webhookProperties);
    }

    @Test
    void accepted_path_inserts_event_and_advances_quote_to_PAID() {
        PaymentNotification n = bolt11("q-accept", 10, "preimage-accept");
        when(mintQuoteRepository.findById("q-accept"))
                .thenReturn(Optional.of(stub("q-accept", 10L, LifecycleState.PENDING)));
        when(mintQuoteRepository.casLifecycle("q-accept", LifecycleState.PENDING, LifecycleState.PAID))
                .thenReturn(1);
        when(webhookEventRepository.findById(eq(PROVIDER), eq("preimage-accept")))
                .thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        assertThat(outcome.firstAccepted()).isTrue();
        assertThat(updater.isPaid("q-accept")).isTrue();

        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).insert(captor.capture());
        WebhookEvent persisted = captor.getValue();
        assertThat(persisted.outcome()).isEqualTo(Outcome.accepted);
        assertThat(persisted.provider()).isEqualTo(PROVIDER);
        assertThat(persisted.providerEventId()).isEqualTo("preimage-accept");
        assertThat(outcomeCount(Outcome.accepted)).isEqualTo(1);
    }

    @Test
    void accepted_persisted_with_quote_unit_not_hardcoded_sat() {
        // Quote is in EUR; persisted row MUST carry "eur", not the hardcoded "sat".
        PaymentNotification n = bolt11("q-eur", 10, "preimage-eur");
        MintQuote eurQuote = new MintQuoteStub("q-eur", 10L, "eur", "https://mint.example",
                "bolt11", "q-eur", LifecycleState.PENDING, "0".repeat(64));
        when(mintQuoteRepository.findById("q-eur")).thenReturn(Optional.of(eurQuote));
        when(mintQuoteRepository.casLifecycle("q-eur", LifecycleState.PENDING, LifecycleState.PAID))
                .thenReturn(1);
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).insert(captor.capture());
        assertThat(captor.getValue().unit())
                .as("persisted unit must match the quote's unit, not be hardcoded")
                .isEqualTo("eur");
    }

    /**
     * A missing amount is {@code invalid_amount}, not {@code amount_mismatch} (#469).
     *
     * <p>These asserted {@code amount_mismatch} until the two were split. The
     * label mattered: nine zero-amount invoices surfaced as 9962
     * {@code amount_mismatch} events, and the first diagnosis read that as a
     * unit or scale disagreement between the adapter and the mint rather than
     * as an amount that was never there.
     */
    @Test
    void null_amount_is_rejected_before_persisting_any_event() {
        PaymentNotification n = PaymentNotification.builder()
                .quoteId("q-null").paymentMethod("bolt11").amount(null).preimage("p-null").build();

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.invalid_amount);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        assertThat(outcomeCount(Outcome.invalid_amount)).isEqualTo(1);
        assertThat(outcomeCount(Outcome.amount_mismatch))
                .as("a missing amount must not be counted as a disagreement about its size")
                .isZero();
    }

    @Test
    void zero_amount_is_rejected_before_persisting_any_event() {
        PaymentNotification n = bolt11("q-zero", 0, "p-zero");

        WebhookOutcome outcome = updater.record(n);

        // THE REGRESSION SHAPE. A fee that floored to zero invoiced nothing,
        // the payment settled trivially, and this refusal is what the mint
        // logged 9962 times overnight on staging.
        assertThat(outcome.outcome()).isEqualTo(Outcome.invalid_amount);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
    }

    @Test
    void negative_amount_is_rejected_before_persisting_any_event() {
        PaymentNotification n = bolt11("q-neg", -5, "p-neg");

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.invalid_amount);
        verify(webhookEventRepository, never()).insert(any());
    }

    @Test
    void amount_mismatch_persists_with_mismatch_outcome_and_leaves_quote_state_untouched() {
        PaymentNotification n = bolt11("q-amount", 9, "preimage-amount");
        when(mintQuoteRepository.findById("q-amount"))
                .thenReturn(Optional.of(stub("q-amount", 10L, LifecycleState.PENDING)));
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.amount_mismatch);
        assertThat(outcome.firstAccepted()).isFalse();
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        assertThat(outcomeCount(Outcome.amount_mismatch)).isEqualTo(1);
    }

    @Test
    void method_mismatch_persists_method_mismatch() {
        PaymentNotification n = PaymentNotification.builder()
                .quoteId("q-method").paymentMethod("cash").amount(10).preimage("p-method").build();
        when(mintQuoteRepository.findById("q-method"))
                .thenReturn(Optional.of(stub("q-method", 10L, LifecycleState.PENDING))); // bolt11
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.method_mismatch);
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
    }

    @Test
    void orphan_when_quote_unknown() {
        PaymentNotification n = bolt11("q-orphan", 10, "p-orphan");
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(mintQuoteRepository.findById("q-orphan")).thenReturn(Optional.empty());

        WebhookOutcome outcome = updater.record(n);
        assertThat(outcome.outcome()).isEqualTo(Outcome.orphan);
    }

    @Test
    void voucher_payment_accepted_when_amount_matches_charged_amount() {
        // Spec 006 — a voucher-quote payment (mint_quote misses, voucher_quote
        // hits) with amount == charged_amount is now classified accepted (was
        // orphan), so VoucherFundingResolverImpl can bind the CUSTOMER_PAYMENT.
        PaymentNotification n = bolt11("v-accept", 50, "preimage-v-accept");
        when(mintQuoteRepository.findById("v-accept")).thenReturn(Optional.empty());
        when(voucherQuoteRepository.findById("v-accept"))
                .thenReturn(Optional.of(voucherStub("v-accept", /*face*/ 1000L, /*charged*/ 50L, "sat")));
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(webhookEventRepository).insert(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(Outcome.accepted);
        assertThat(captor.getValue().quoteId()).isEqualTo("v-accept");
        // unit inherited from the voucher quote, not hardcoded "sat" default.
        assertThat(captor.getValue().unit()).isEqualTo("sat");
        // No mint_quote lifecycle CAS — vouchers have their own state machine.
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
    }

    /**
     * Issue #459 — the customer's payment must fund the voucher by itself. This
     * is the defect the 1000 EUR sale exposed: funding used to be created only
     * when a client sent a mint request, so a client that stopped polling left
     * the quote UNFUNDED with the money taken.
     */
    @Test
    void voucher_funding_is_attached_by_the_webhook_without_any_client_request() {
        PaymentNotification n = bolt11("v-fund", 50, "preimage-v-fund");
        VoucherQuote quote = voucherStub("v-fund", 1000L, 50L, "sat");
        when(mintQuoteRepository.findById("v-fund")).thenReturn(Optional.empty());
        when(voucherQuoteRepository.findById("v-fund")).thenReturn(Optional.of(quote));
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(voucherFundingResolver.resolveForQuote(quote))
                .thenReturn(Optional.of(fundingStub("f-1", 50L)));
        when(voucherQuoteRepository.attachFundingAndAdvance("v-fund", "f-1")).thenReturn(1);

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        verify(voucherQuoteRepository).attachFundingAndAdvance("v-fund", "f-1");
    }

    /**
     * Reproduces the incident directly: a 1000 EUR sale auto-split into five
     * 200 EUR parts, all paid within two seconds. Previously only the two parts
     * whose webhooks landed inside the client's polling window were funded and
     * the customer received 200 of 1000. Every part must now fund itself.
     */
    @Test
    void every_part_of_a_five_way_split_is_funded_even_though_the_client_polls_for_none() {
        for (int part = 0; part < 5; part++) {
            String quoteId = "v-split-" + part;
            String fundingId = "f-split-" + part;
            VoucherQuote quote = voucherStub(quoteId, 20_000L, 50L, "sat");
            when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.empty());
            when(voucherQuoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));
            when(voucherFundingResolver.resolveForQuote(quote))
                    .thenReturn(Optional.of(fundingStub(fundingId, 50L)));
            when(voucherQuoteRepository.attachFundingAndAdvance(quoteId, fundingId)).thenReturn(1);
        }
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        for (int part = 0; part < 5; part++) {
            WebhookOutcome outcome = updater.record(
                    bolt11("v-split-" + part, 50, "preimage-split-" + part));
            assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        }

        for (int part = 0; part < 5; part++) {
            verify(voucherQuoteRepository)
                    .attachFundingAndAdvance("v-split-" + part, "f-split-" + part);
        }
    }

    /**
     * A client mint request racing the webhook wins the CAS; the webhook must
     * treat that as a no-op rather than attaching a second funding row.
     */
    @Test
    void voucher_funding_attach_is_a_no_op_when_a_concurrent_client_request_wins_the_cas() {
        PaymentNotification n = bolt11("v-race", 50, "preimage-v-race");
        VoucherQuote quote = voucherStub("v-race", 1000L, 50L, "sat");
        when(mintQuoteRepository.findById("v-race")).thenReturn(Optional.empty());
        when(voucherQuoteRepository.findById("v-race")).thenReturn(Optional.of(quote));
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(voucherFundingResolver.resolveForQuote(quote))
                .thenReturn(Optional.of(fundingStub("f-race", 50L)));
        // 0 = the row was no longer UNFUNDED.
        when(voucherQuoteRepository.attachFundingAndAdvance("v-race", "f-race")).thenReturn(0);

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        verify(voucherQuoteRepository, times(1)).attachFundingAndAdvance("v-race", "f-race");
    }

    /**
     * The payment is real whether or not the funding attach succeeds, so a
     * resolver failure must still leave an accepted event behind. Failing the
     * delivery would invite a retry that classifies as a duplicate and still
     * leaves the quote unfunded; the reconciler is what recovers it.
     */
    @Test
    void voucher_payment_is_still_accepted_when_the_funding_attach_fails() {
        PaymentNotification n = bolt11("v-boom", 50, "preimage-v-boom");
        VoucherQuote quote = voucherStub("v-boom", 1000L, 50L, "sat");
        when(mintQuoteRepository.findById("v-boom")).thenReturn(Optional.empty());
        when(voucherQuoteRepository.findById("v-boom")).thenReturn(Optional.of(quote));
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(voucherFundingResolver.resolveForQuote(quote))
                .thenThrow(new RuntimeException("funding store unreachable"));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.accepted);
        verify(webhookEventRepository).insert(any());
    }

    @Test
    void voucher_payment_amount_mismatch_when_amount_differs_from_charged_amount() {
        // The customer must pay exactly the voucher's charged_amount (the fee).
        PaymentNotification n = bolt11("v-mismatch", 49, "preimage-v-mismatch");
        when(mintQuoteRepository.findById("v-mismatch")).thenReturn(Optional.empty());
        when(voucherQuoteRepository.findById("v-mismatch"))
                .thenReturn(Optional.of(voucherStub("v-mismatch", 1000L, 50L, "sat")));
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.amount_mismatch);
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
    }

    @Test
    void orphan_when_neither_mint_quote_nor_voucher_quote_matches() {
        PaymentNotification n = bolt11("v-orphan", 10, "p-v-orphan");
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(mintQuoteRepository.findById("v-orphan")).thenReturn(Optional.empty());
        when(voucherQuoteRepository.findById("v-orphan")).thenReturn(Optional.empty());

        WebhookOutcome outcome = updater.record(n);
        assertThat(outcome.outcome()).isEqualTo(Outcome.orphan);
    }

    @Test
    void duplicate_when_same_provider_event_id_with_matching_body() {
        PaymentNotification n = bolt11("q-dup", 10, "p-dup");
        when(webhookEventRepository.findById(PROVIDER, "p-dup"))
                .thenReturn(Optional.of(eventRow("q-dup", 10L, "bolt11", Outcome.accepted)));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.duplicate);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        assertThat(outcomeCount(Outcome.duplicate)).isEqualTo(1);
    }

    @Test
    void tamper_when_same_event_id_paired_with_different_amount() {
        PaymentNotification n = bolt11("q-tamper", 11, "p-tamper");
        when(webhookEventRepository.findById(PROVIDER, "p-tamper"))
                .thenReturn(Optional.of(eventRow("q-tamper", 10L, "bolt11", Outcome.accepted)));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.tamper);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        assertThat(outcomeCount(Outcome.tamper)).isEqualTo(1);
    }

    @Test
    void expired_when_quote_state_is_expired() {
        PaymentNotification n = bolt11("q-expired", 10, "p-expired");
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(mintQuoteRepository.findById("q-expired"))
                .thenReturn(Optional.of(stub("q-expired", 10L, LifecycleState.EXPIRED)));

        WebhookOutcome outcome = updater.record(n);
        assertThat(outcome.outcome()).isEqualTo(Outcome.expired);
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
    }

    @Test
    void noop_when_quote_already_paid() {
        PaymentNotification n = bolt11("q-noop", 10, "p-noop");
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(mintQuoteRepository.findById("q-noop"))
                .thenReturn(Optional.of(stub("q-noop", 10L, LifecycleState.PAID)));

        WebhookOutcome outcome = updater.record(n);
        assertThat(outcome.outcome()).isEqualTo(Outcome.noop);
    }

    @Test
    void cas_failure_after_lookup_yields_noop() {
        PaymentNotification n = bolt11("q-race", 10, "p-race");
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(mintQuoteRepository.findById("q-race"))
                .thenReturn(Optional.of(stub("q-race", 10L, LifecycleState.PENDING)))
                .thenReturn(Optional.of(stub("q-race", 10L, LifecycleState.PAID)));
        when(mintQuoteRepository.casLifecycle(eq("q-race"), eq(LifecycleState.PENDING), eq(LifecycleState.PAID)))
                .thenReturn(0);
        when(mintQuoteRepository.casLifecycle(eq("q-race"), eq(LifecycleState.UNPAID), eq(LifecycleState.PAID)))
                .thenReturn(0);

        WebhookOutcome outcome = updater.record(n);
        assertThat(outcome.outcome()).isEqualTo(Outcome.noop);
    }

    @Test
    void duplicate_event_exception_during_insert_falls_back_to_replay_classification() {
        PaymentNotification n = bolt11("q-race-insert", 10, "p-race-insert");
        when(webhookEventRepository.findById(PROVIDER, "p-race-insert")).thenReturn(Optional.empty());
        when(mintQuoteRepository.findById("q-race-insert"))
                .thenReturn(Optional.of(stub("q-race-insert", 10L, LifecycleState.PENDING)));
        when(mintQuoteRepository.casLifecycle(eq("q-race-insert"), eq(LifecycleState.PENDING), eq(LifecycleState.PAID)))
                .thenReturn(1);
        // The concurrent writer beat us: insert throws and exposes the prior row.
        WebhookEvent prior = eventRow("q-race-insert", 10L, "bolt11", Outcome.accepted);
        when(webhookEventRepository.insert(any()))
                .thenThrow(new WebhookEventRepository.DuplicateEventException(prior));

        WebhookOutcome outcome = updater.record(n);
        assertThat(outcome.outcome()).isEqualTo(Outcome.duplicate);
    }

    @Test
    void counter_increments_once_per_call() {
        PaymentNotification n = bolt11("q-counter", 10, "p-counter");
        when(mintQuoteRepository.findById("q-counter"))
                .thenReturn(Optional.of(stub("q-counter", 10L, LifecycleState.PENDING)));
        when(mintQuoteRepository.casLifecycle(eq("q-counter"), eq(LifecycleState.PENDING), eq(LifecycleState.PAID)))
                .thenReturn(1);
        when(webhookEventRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookEventRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        updater.record(n);
        assertThat(outcomeCount(Outcome.accepted)).isEqualTo(1);
        verify(webhookEventRepository, times(1)).insert(any());
    }

    // ---------------------- helpers ----------------------

    private static PaymentNotification bolt11(String quoteId, int amount, String preimage) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }

    private static MintQuote stub(String quoteId, long amount, LifecycleState state) {
        return new MintQuoteStub(quoteId, amount, "sat", "https://mint.example",
                "bolt11", quoteId, state, "0".repeat(64));
    }

    private static WebhookEvent eventRow(String quoteId, long amount, String paymentMethod, Outcome outcome) {
        return new EventStub("phoenixd", "stored-event-id", quoteId, amount, "sat",
                paymentMethod, null, outcome, Instant.now());
    }

    private static VoucherQuote voucherStub(String quoteId, long faceValue, long chargedAmount, String unit) {
        return new VoucherQuoteStub(quoteId, faceValue, chargedAmount, unit);
    }

    private static VoucherFunding fundingStub(String fundingId, long amount) {
        return new VoucherFundingStub(fundingId, amount);
    }

    private record VoucherFundingStub(String fundingId, long amount) implements VoucherFunding {
        @Override public VoucherFundingSource fundingSource() {
            return VoucherFundingSource.CUSTOMER_PAYMENT;
        }
        @Override public String unit() { return "sat"; }
        @Override public Instant createdAt() { return Instant.now(); }
    }

    private record VoucherQuoteStub(String quoteId, long faceValue, long chargedAmount, String unit)
            implements VoucherQuote {
        @Override public String voucherType() { return "customer_paid"; }
        @Override public long fee() { return chargedAmount; }
        @Override public String merchantId() { return null; }
        @Override public String customerId() { return null; }
        @Override public String fundingId() { return null; }
        @Override public VoucherLifecycleState lifecycleState() { return VoucherLifecycleState.UNFUNDED; }
        @Override public String idempotencyKey() { return null; }
        @Override public String requestHash() { return "0".repeat(64); }
        @Override public Instant createdAt() { return null; }
        @Override public Instant updatedAt() { return null; }
    }

    private record MintQuoteStub(String quoteId, long amount, String unit, String mintUrl,
                                 String paymentMethod, String invoiceId, LifecycleState lifecycleState,
                                 String requestHash) implements MintQuote {
        @Override public Instant createdAt() { return null; }
        @Override public Instant updatedAt() { return null; }
    }

    private record EventStub(String provider, String providerEventId, String quoteId, long amount,
                             String unit, String paymentMethod, String signatureDigest,
                             Outcome outcome, Instant receivedAt) implements WebhookEvent {
    }
}
