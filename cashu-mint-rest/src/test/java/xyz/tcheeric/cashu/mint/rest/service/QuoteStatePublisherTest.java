package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.common.nut17.QuoteStatePayload;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.mint.rest.event.QuoteStateChangeEvent;

import static org.mockito.Mockito.*;

/**
 * Tests QuoteStatePublisher event listener functionality.
 */
@ExtendWith(MockitoExtension.class)
class QuoteStatePublisherTest {

    private QuoteStatePublisher quoteStatePublisher;

    @Mock
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() {
        quoteStatePublisher = new QuoteStatePublisher(subscriptionManager);
    }

    // Tests that mint quote event is published to subscription manager.
    @Test
    void onQuoteStateChange_MintQuote_PublishesToSubscriptionManager() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("quote-123");
        payload.setState("PAID");
        payload.setPaid(true);

        QuoteStateChangeEvent event = new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_mint_quote, "quote-123", payload);

        quoteStatePublisher.onQuoteStateChange(event);

        verify(subscriptionManager).publishQuoteState(
                SubscriptionKind.bolt11_mint_quote, "quote-123", payload);
    }

    // Tests that melt quote event is published to subscription manager.
    @Test
    void onQuoteStateChange_MeltQuote_PublishesToSubscriptionManager() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("melt-456");
        payload.setState("PENDING");
        payload.setPaid(false);

        QuoteStateChangeEvent event = new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_melt_quote, "melt-456", payload);

        quoteStatePublisher.onQuoteStateChange(event);

        verify(subscriptionManager).publishQuoteState(
                SubscriptionKind.bolt11_melt_quote, "melt-456", payload);
    }

    // Tests that exception from subscription manager is handled gracefully.
    @Test
    void onQuoteStateChange_ExceptionHandled_DoesNotThrow() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("quote-err");
        payload.setState("PAID");

        QuoteStateChangeEvent event = new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_mint_quote, "quote-err", payload);

        doThrow(new RuntimeException("Test error")).when(subscriptionManager)
                .publishQuoteState(any(), anyString(), any());

        // Should not throw - error is logged
        quoteStatePublisher.onQuoteStateChange(event);

        verify(subscriptionManager).publishQuoteState(
                SubscriptionKind.bolt11_mint_quote, "quote-err", payload);
    }

    // Tests that UNPAID state is published correctly.
    @Test
    void onQuoteStateChange_UnpaidState_PublishesCorrectly() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("quote-unpaid");
        payload.setState("UNPAID");
        payload.setPaid(false);

        QuoteStateChangeEvent event = new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_mint_quote, "quote-unpaid", payload);

        quoteStatePublisher.onQuoteStateChange(event);

        verify(subscriptionManager).publishQuoteState(
                SubscriptionKind.bolt11_mint_quote, "quote-unpaid", payload);
    }

    // Tests that ISSUED state is published correctly for completed mint quotes.
    @Test
    void onQuoteStateChange_IssuedState_PublishesCorrectly() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("quote-issued");
        payload.setState("ISSUED");
        payload.setPaid(true);

        QuoteStateChangeEvent event = new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_mint_quote, "quote-issued", payload);

        quoteStatePublisher.onQuoteStateChange(event);

        verify(subscriptionManager).publishQuoteState(
                SubscriptionKind.bolt11_mint_quote, "quote-issued", payload);
    }

    // Tests that payload with request field is passed through.
    @Test
    void onQuoteStateChange_PayloadWithRequest_PassedThrough() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("quote-with-req");
        payload.setState("UNPAID");
        payload.setRequest("lnbc100n1...");
        payload.setExpiry(3600L);

        QuoteStateChangeEvent event = new QuoteStateChangeEvent(
                this, SubscriptionKind.bolt11_mint_quote, "quote-with-req", payload);

        quoteStatePublisher.onQuoteStateChange(event);

        verify(subscriptionManager).publishQuoteState(
                eq(SubscriptionKind.bolt11_mint_quote), eq("quote-with-req"), same(payload));
    }
}
