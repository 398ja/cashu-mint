package xyz.tcheeric.cashu.mint.webhook;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
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
    private WebhookEventRepository webhookEventRepository;
    private WebhookProperties webhookProperties;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        mintQuoteRepository = Mockito.mock(MintQuoteRepository.class);
        webhookEventRepository = Mockito.mock(WebhookEventRepository.class);
        webhookProperties = new WebhookProperties();
        webhookProperties.setProvider(PROVIDER);
        meterRegistry = new SimpleMeterRegistry();

        updater = new QuoteStatusUpdater(
                Duration.ofHours(1), Duration.ofHours(24), 10_485_760L, 100_000,
                meterRegistry, mintQuoteRepository, webhookEventRepository, webhookProperties);
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
        assertThat(meterRegistry.counter("cashu_mint_webhook_event_total",
                "outcome", "accepted").count()).isEqualTo(1.0);
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

    @Test
    void null_amount_is_rejected_before_persisting_any_event() {
        PaymentNotification n = PaymentNotification.builder()
                .quoteId("q-null").paymentMethod("bolt11").amount(null).preimage("p-null").build();

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.amount_mismatch);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        assertThat(meterRegistry.counter("cashu_mint_webhook_event_total",
                "outcome", "amount_mismatch").count()).isEqualTo(1.0);
    }

    @Test
    void zero_amount_is_rejected_before_persisting_any_event() {
        PaymentNotification n = bolt11("q-zero", 0, "p-zero");

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.amount_mismatch);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
    }

    @Test
    void negative_amount_is_rejected_before_persisting_any_event() {
        PaymentNotification n = bolt11("q-neg", -5, "p-neg");

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.amount_mismatch);
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
        assertThat(meterRegistry.counter("cashu_mint_webhook_event_total",
                "outcome", "amount_mismatch").count()).isEqualTo(1.0);
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
    void duplicate_when_same_provider_event_id_with_matching_body() {
        PaymentNotification n = bolt11("q-dup", 10, "p-dup");
        when(webhookEventRepository.findById(PROVIDER, "p-dup"))
                .thenReturn(Optional.of(eventRow("q-dup", 10L, "bolt11", Outcome.accepted)));

        WebhookOutcome outcome = updater.record(n);

        assertThat(outcome.outcome()).isEqualTo(Outcome.duplicate);
        verify(webhookEventRepository, never()).insert(any());
        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        assertThat(meterRegistry.counter("cashu_mint_webhook_event_total",
                "outcome", "duplicate").count()).isEqualTo(1.0);
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
        assertThat(meterRegistry.counter("cashu_mint_webhook_event_total",
                "outcome", "tamper").count()).isEqualTo(1.0);
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
        assertThat(meterRegistry.counter("cashu_mint_webhook_event_total",
                "outcome", "accepted").count()).isEqualTo(1.0);
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
