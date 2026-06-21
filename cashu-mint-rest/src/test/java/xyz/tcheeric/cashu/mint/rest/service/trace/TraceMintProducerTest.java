package xyz.tcheeric.cashu.mint.rest.service.trace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.cashu.ledger.trace.core.OperationKind;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.InMemoryOperationIdRegistry;
import java.util.List;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceEventSigner;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceProofInput;

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

    // MINT_FAILED carries no proofs; MELT_FAILED carries the released input Ys.
    @Test
    void failedEvents_haveExpectedProofShape() {
        TraceabilityPublisher publisher = mock(TraceabilityPublisher.class);
        TraceMintProducer producer = producer(publisher);

        producer.onMintFailed(new TraceMintFailedEvent(
                this, "q-mf", 10L, "sat", "mint_invoice_not_paid_error", "x", Instant.now()));
        String y = "02" + "b".repeat(64);
        producer.onMeltFailed(new TraceMeltFailedEvent(
                this, "q-Mf", 5L, "sat", List.of(new TraceProofInput(5L, "00ad268c4d1f5826", y)),
                "melt_invoice_not_paid_error", "x", Instant.now()));

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(publisher, times(2)).publish(captor.capture());
        TransactionEvent mintFailed = captor.getAllValues().get(0);
        TransactionEvent meltFailed = captor.getAllValues().get(1);
        assert mintFailed.kind() == OperationKind.MINT_FAILED;
        assert mintFailed.inputs().isEmpty();
        assert meltFailed.kind() == OperationKind.MELT_FAILED;
        assert meltFailed.inputs().size() == 1;
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

    // A fault in the event build itself (e.g. the SQLite operation-id registry) is
    // inside the guard too and must not propagate — the build runs via a Supplier.
    @Test
    void buildFault_isSwallowed() {
        TraceabilityPublisher publisher = mock(TraceabilityPublisher.class);
        TraceEventFactory factory = mock(TraceEventFactory.class);
        doThrow(new RuntimeException("registry down")).when(factory).buildMintQuoteRequested(any());
        TraceMintProducer producer = new TraceMintProducer(publisher, factory);

        assertThatCode(() -> producer.onMintQuoteRequested(mintQuoteEvent()))
                .doesNotThrowAnyException();
        verify(publisher, never()).publish(any());
    }
}
