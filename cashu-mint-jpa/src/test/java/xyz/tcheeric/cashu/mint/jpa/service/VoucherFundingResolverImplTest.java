package xyz.tcheeric.cashu.mint.jpa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherFundingJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec 003 T101 — unit tests for the funding resolver. Covers the three
 * decision paths: existing funding row, lazy create from accepted
 * webhook event, and no funding (returns empty).
 */
class VoucherFundingResolverImplTest {

    private VoucherFundingJpaRepository fundingJpa;
    private WebhookEventJpaRepository webhookJpa;
    private VoucherFundingResolverImpl resolver;

    @BeforeEach
    void setUp() {
        fundingJpa = mock(VoucherFundingJpaRepository.class);
        webhookJpa = mock(WebhookEventJpaRepository.class);
        resolver = new VoucherFundingResolverImpl(fundingJpa, webhookJpa);
    }

    @Test
    void returnsExistingFundingRow_whenQuoteAlreadyHasFundingId() {
        // existing funding_id ⇒ resolver should just return it without scanning webhooks
        VoucherQuote quote = stubQuote("q-1", "fund-existing", VoucherLifecycleState.FUNDED);
        CustomerPaymentFundingEntity funding = sampleFunding("fund-existing");
        when(fundingJpa.findById("fund-existing")).thenReturn(Optional.of(funding));

        Optional<VoucherFunding> result = resolver.resolveForQuote(quote);

        assertThat(result).isPresent();
        assertThat(result.get().fundingId()).isEqualTo("fund-existing");
        verifyNoInteractions(webhookJpa);
    }

    @Test
    void lazyCreatesCustomerPaymentFunding_whenAcceptedWebhookExistsButFundingMissing() {
        // funding_id null + accepted webhook present ⇒ resolver creates a CustomerPaymentFunding row
        VoucherQuote quote = stubQuote("q-2", null, VoucherLifecycleState.UNFUNDED);
        WebhookEventEntity webhook = stubWebhookEvent("phoenixd", "evt-42", "q-2", 1000L, "sat");
        when(webhookJpa.findFirstAcceptedByQuoteId("q-2")).thenReturn(Optional.of(webhook));
        when(fundingJpa.findByProviderEventId("phoenixd", "evt-42")).thenReturn(Optional.empty());
        when(fundingJpa.save(any(CustomerPaymentFundingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<VoucherFunding> result = resolver.resolveForQuote(quote);

        assertThat(result).isPresent();
        ArgumentCaptor<CustomerPaymentFundingEntity> captor =
                ArgumentCaptor.forClass(CustomerPaymentFundingEntity.class);
        verify(fundingJpa, times(1)).save(captor.capture());
        CustomerPaymentFundingEntity saved = captor.getValue();
        assertThat(saved.getProvider()).isEqualTo("phoenixd");
        assertThat(saved.getProviderEventId()).isEqualTo("evt-42");
        assertThat(saved.getAmount()).isEqualTo(1000L);
        assertThat(saved.getUnit()).isEqualTo("sat");
        assertThat(saved.getWebhookEventQuoteId()).isEqualTo("q-2");
    }

    @Test
    void reusesExistingCustomerPaymentFunding_whenWebhookProviderEventIdAlreadyMapped() {
        // resolver fallback MUST NOT create a duplicate funding row for the same provider event
        VoucherQuote quote = stubQuote("q-3", null, VoucherLifecycleState.UNFUNDED);
        WebhookEventEntity webhook = stubWebhookEvent("phoenixd", "evt-99", "q-3", 500L, "sat");
        CustomerPaymentFundingEntity existing = sampleFunding("fund-99");
        existing.setProvider("phoenixd");
        existing.setProviderEventId("evt-99");
        when(webhookJpa.findFirstAcceptedByQuoteId("q-3")).thenReturn(Optional.of(webhook));
        when(fundingJpa.findByProviderEventId("phoenixd", "evt-99")).thenReturn(Optional.of(existing));

        Optional<VoucherFunding> result = resolver.resolveForQuote(quote);

        assertThat(result).isPresent();
        assertThat(result.get().fundingId()).isEqualTo("fund-99");
        verify(fundingJpa, never()).save(any());
    }

    @Test
    void returnsEmpty_whenNoFundingAndNoAcceptedWebhook() {
        // FR-002: funding gate must surface empty so MintTask rejects with funding_required
        VoucherQuote quote = stubQuote("q-4", null, VoucherLifecycleState.UNFUNDED);
        when(webhookJpa.findFirstAcceptedByQuoteId("q-4")).thenReturn(Optional.empty());

        Optional<VoucherFunding> result = resolver.resolveForQuote(quote);

        assertThat(result).isEmpty();
        verify(fundingJpa, never()).save(any());
    }

    @Test
    void returnsEmpty_whenQuoteIsNull() {
        // defensive: a null quote should not NPE
        assertThat(resolver.resolveForQuote(null)).isEmpty();
    }

    // ---------------------------------------------------------------
    // Test fixtures
    // ---------------------------------------------------------------

    private static VoucherQuote stubQuote(String id, String fundingId, VoucherLifecycleState state) {
        VoucherQuote quote = mock(VoucherQuote.class);
        when(quote.quoteId()).thenReturn(id);
        when(quote.fundingId()).thenReturn(fundingId);
        when(quote.lifecycleState()).thenReturn(state);
        when(quote.customerId()).thenReturn(null);
        return quote;
    }

    private static CustomerPaymentFundingEntity sampleFunding(String id) {
        CustomerPaymentFundingEntity f = new CustomerPaymentFundingEntity();
        f.setFundingId(id);
        f.setAmount(1000L);
        f.setUnit("sat");
        f.setFundingSource(VoucherFundingSource.CUSTOMER_PAYMENT);
        f.setProvider("phoenixd");
        f.setProviderEventId("evt-" + id);
        return f;
    }

    private static WebhookEventEntity stubWebhookEvent(String provider, String eventId,
                                                       String quoteId, long amount, String unit) {
        WebhookEventEntity e = mock(WebhookEventEntity.class);
        when(e.provider()).thenReturn(provider);
        when(e.providerEventId()).thenReturn(eventId);
        when(e.quoteId()).thenReturn(quoteId);
        when(e.amount()).thenReturn(amount);
        when(e.unit()).thenReturn(unit);
        when(e.outcome()).thenReturn(WebhookEvent.Outcome.accepted);
        when(e.receivedAt()).thenReturn(Instant.now());
        return e;
    }
}
