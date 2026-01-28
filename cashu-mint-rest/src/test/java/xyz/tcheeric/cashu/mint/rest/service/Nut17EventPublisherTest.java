package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut17.QuoteStatePayload;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.rest.event.ProofStateChangeEvent;
import xyz.tcheeric.cashu.mint.rest.event.QuoteStateChangeEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests Nut17EventPublisher event publishing functionality.
 */
@ExtendWith(MockitoExtension.class)
class Nut17EventPublisherTest {

    private Nut17EventPublisher nut17EventPublisher;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @BeforeEach
    void setUp() {
        nut17EventPublisher = new Nut17EventPublisher(applicationEventPublisher);
    }

    // Tests that publishProofState emits a ProofStateChangeEvent.
    @Test
    void publishProofState_EmitsProofStateChangeEvent() {
        nut17EventPublisher.publishProofState("test-y-value", "SPENT", null);

        ArgumentCaptor<ProofStateChangeEvent> captor = ArgumentCaptor.forClass(ProofStateChangeEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        ProofStateChangeEvent event = captor.getValue();
        assertEquals("test-y-value", event.getY());
        assertEquals("SPENT", event.getState());
        assertNull(event.getWitness());
    }

    // Tests that publishProofState includes witness data.
    @Test
    void publishProofState_WithWitness_IncludesWitness() {
        String witness = "{\"signatures\":[\"sig1\"]}";
        nut17EventPublisher.publishProofState("y-123", "PENDING", witness);

        ArgumentCaptor<ProofStateChangeEvent> captor = ArgumentCaptor.forClass(ProofStateChangeEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        ProofStateChangeEvent event = captor.getValue();
        assertEquals("y-123", event.getY());
        assertEquals("PENDING", event.getState());
        assertEquals(witness, event.getWitness());
    }

    // Tests that publishProofsSpent emits events for each proof.
    @Test
    void publishProofsSpent_EmitsEventForEachProof() {
        Proof<RandomStringSecret> proof1 = createProof();
        Proof<RandomStringSecret> proof2 = createProof();

        nut17EventPublisher.publishProofsSpent(List.of(proof1, proof2));

        verify(applicationEventPublisher, times(2)).publishEvent(any(ProofStateChangeEvent.class));
    }

    // Tests that publishProofsSpent uses SPENT state.
    @Test
    void publishProofsSpent_UsesSpentState() {
        Proof<RandomStringSecret> proof = createProof();

        nut17EventPublisher.publishProofsSpent(List.of(proof));

        ArgumentCaptor<ProofStateChangeEvent> captor = ArgumentCaptor.forClass(ProofStateChangeEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        assertEquals(NUT07.SPENT, captor.getValue().getState());
    }

    // Tests that publishProofsPending emits events with PENDING state.
    @Test
    void publishProofsPending_UsesPendingState() {
        Proof<RandomStringSecret> proof = createProof();

        nut17EventPublisher.publishProofsPending(List.of(proof));

        ArgumentCaptor<ProofStateChangeEvent> captor = ArgumentCaptor.forClass(ProofStateChangeEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        assertEquals(NUT07.PENDING, captor.getValue().getState());
    }

    // Tests that publishMintQuoteState emits QuoteStateChangeEvent with mint kind.
    @Test
    void publishMintQuoteState_EmitsQuoteEventWithMintKind() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("mint-quote-123");
        payload.setState("PAID");
        payload.setPaid(true);

        nut17EventPublisher.publishMintQuoteState("mint-quote-123", payload);

        ArgumentCaptor<QuoteStateChangeEvent> captor = ArgumentCaptor.forClass(QuoteStateChangeEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        QuoteStateChangeEvent event = captor.getValue();
        assertEquals(SubscriptionKind.bolt11_mint_quote, event.getKind());
        assertEquals("mint-quote-123", event.getQuoteId());
        assertSame(payload, event.getPayload());
    }

    // Tests that publishMeltQuoteState emits QuoteStateChangeEvent with melt kind.
    @Test
    void publishMeltQuoteState_EmitsQuoteEventWithMeltKind() {
        QuoteStatePayload payload = new QuoteStatePayload();
        payload.setQuoteId("melt-quote-456");
        payload.setState("PENDING");
        payload.setPaid(false);

        nut17EventPublisher.publishMeltQuoteState("melt-quote-456", payload);

        ArgumentCaptor<QuoteStateChangeEvent> captor = ArgumentCaptor.forClass(QuoteStateChangeEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        QuoteStateChangeEvent event = captor.getValue();
        assertEquals(SubscriptionKind.bolt11_melt_quote, event.getKind());
        assertEquals("melt-quote-456", event.getQuoteId());
        assertSame(payload, event.getPayload());
    }

    // Tests that empty proof list doesn't emit events.
    @Test
    void publishProofsSpent_EmptyList_NoEventsEmitted() {
        nut17EventPublisher.publishProofsSpent(List.of());

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // Tests that publishProofsPending with empty list doesn't emit events.
    @Test
    void publishProofsPending_EmptyList_NoEventsEmitted() {
        nut17EventPublisher.publishProofsPending(List.of());

        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    private Proof<RandomStringSecret> createProof() {
        Proof<RandomStringSecret> proof = new Proof<>();
        proof.setSecret(RandomStringSecret.create());
        proof.setAmount(100);
        proof.setKeySetId("keyset-id");
        return proof;
    }
}
