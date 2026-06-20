package xyz.tcheeric.cashu.mint.rest.service.trace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.InMemoryOperationIdRegistry;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;

/**
 * Spec 036 US1 / US3 — verifies the producer publishes exactly one valid event
 * per quote and that publishing faults are swallowed (FR-007).
 */
class TraceMintProducerTest {

    private static final String TEST_PRIVATE_KEY =
            "0000000000000000000000000000000000000000000000000000000000000001";

    private TraceMintProducer producer(TraceabilityPublisher publisher) {
        TraceEventFactory factory = new TraceEventFactory("https://mint.test",
                new InMemoryOperationIdRegistry(), new TraceEventSigner(TEST_PRIVATE_KEY));
        return new TraceMintProducer(publisher, factory);
    }

    private TraceMintQuoteRequestedEvent mintQuoteEvent() {
        return new TraceMintQuoteRequestedEvent(
                this, "quote-1", "lnbc100n1...", null, 100L, "sat", 0, Instant.now());
    }

    // One mint-quote application event in => exactly one publish(...) of the
    // expected kind.
    @Test
    void onMintQuoteRequested_publishesOneEvent() {
        TraceabilityPublisher publisher = mock(TraceabilityPublisher.class);

        producer(publisher).onMintQuoteRequested(mintQuoteEvent());

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(publisher, times(1)).publish(captor.capture());
        assert captor.getValue().kind() == OperationKind.MINT_QUOTE_REQUESTED;
    }

    // A throwing publisher must never propagate out of the listener (FR-007) —
    // a tracing outage can never fail a mint operation.
    @Test
    void publisherFault_isSwallowed() {
        TraceabilityPublisher publisher = mock(TraceabilityPublisher.class);
        doThrow(new RuntimeException("relay down")).when(publisher).publish(any());

        assertThatCode(() -> producer(publisher).onMintQuoteRequested(mintQuoteEvent()))
                .doesNotThrowAnyException();
    }
}
