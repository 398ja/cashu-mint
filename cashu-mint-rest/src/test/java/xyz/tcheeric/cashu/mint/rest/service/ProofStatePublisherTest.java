package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.mint.rest.event.ProofStateChangeEvent;

import static org.mockito.Mockito.*;

/**
 * Tests ProofStatePublisher event listener functionality.
 */
@ExtendWith(MockitoExtension.class)
class ProofStatePublisherTest {

    private ProofStatePublisher proofStatePublisher;

    @Mock
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() {
        proofStatePublisher = new ProofStatePublisher(subscriptionManager);
    }

    // Tests that event listener publishes proof state to subscription manager.
    @Test
    void onProofStateChange_PublishesToSubscriptionManager() {
        ProofStateChangeEvent event = new ProofStateChangeEvent(this, "test-y-value", "SPENT", null);

        proofStatePublisher.onProofStateChange(event);

        verify(subscriptionManager).publishProofState("test-y-value", "SPENT", null);
    }

    // Tests that witness data is passed through to subscription manager.
    @Test
    void onProofStateChange_WithWitness_PassesWitnessThrough() {
        String witness = "{\"signatures\":[\"sig1\"]}";
        ProofStateChangeEvent event = new ProofStateChangeEvent(this, "y-value", "PENDING", witness);

        proofStatePublisher.onProofStateChange(event);

        verify(subscriptionManager).publishProofState("y-value", "PENDING", witness);
    }

    // Tests that exception from subscription manager is handled gracefully.
    @Test
    void onProofStateChange_ExceptionHandled_DoesNotThrow() {
        ProofStateChangeEvent event = new ProofStateChangeEvent(this, "test-y", "SPENT", null);
        doThrow(new RuntimeException("Test error")).when(subscriptionManager)
                .publishProofState(anyString(), anyString(), any());

        // Should not throw - error is logged
        proofStatePublisher.onProofStateChange(event);

        verify(subscriptionManager).publishProofState("test-y", "SPENT", null);
    }

    // Tests that UNSPENT state is published correctly.
    @Test
    void onProofStateChange_UnspentState_PublishesCorrectly() {
        ProofStateChangeEvent event = new ProofStateChangeEvent(this, "y-123", "UNSPENT", null);

        proofStatePublisher.onProofStateChange(event);

        verify(subscriptionManager).publishProofState("y-123", "UNSPENT", null);
    }

    // Tests that short Y values don't cause substring errors in logging.
    @Test
    void onProofStateChange_ShortYValue_HandledCorrectly() {
        // Y value shorter than 8 chars
        ProofStateChangeEvent event = new ProofStateChangeEvent(this, "abc", "SPENT", null);
        doThrow(new RuntimeException("Test")).when(subscriptionManager)
                .publishProofState(anyString(), anyString(), any());

        // Should not throw - handles short string in logging
        proofStatePublisher.onProofStateChange(event);
    }

    // Tests that null Y value is handled in error logging.
    @Test
    void onProofStateChange_NullYValue_HandledInErrorLogging() {
        ProofStateChangeEvent event = new ProofStateChangeEvent(this, null, "SPENT", null);
        doThrow(new RuntimeException("Test")).when(subscriptionManager)
                .publishProofState(any(), anyString(), any());

        // Should not throw - handles null in logging
        proofStatePublisher.onProofStateChange(event);
    }
}
