package xyz.tcheeric.cashu.mint.rest.service.trace;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.ledger.trace.core.OperationInvariants;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;

/**
 * Spec 036 — mint-side trace producer.
 *
 * <p>Listens for the trace application events published from {@code CashuController}
 * at post-success / known-failure seams and emits signed {@code kind-9079} trace
 * events to the cashu-ledger relays via the SDK's {@link TraceabilityPublisher}.
 *
 * <p>Strictly fire-and-forget: every emission is validated against
 * {@link OperationInvariants} and wrapped in try/catch so a tracing fault can
 * never fail or block a mint/melt operation (FR-007). Only active when
 * {@code cashu.trace.publisher.enabled=true}; when disabled this bean and the
 * SDK publisher bean are both absent, so the published application events are
 * silently dropped and mint behaviour is unchanged.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cashu.trace.publisher", name = "enabled", havingValue = "true")
public class TraceMintProducer {

    private final TraceabilityPublisher publisher;
    private final TraceEventFactory factory;

    public TraceMintProducer(TraceabilityPublisher publisher, TraceEventFactory factory) {
        this.publisher = publisher;
        this.factory = factory;
    }

    /** US1 — emit MINT_QUOTE_REQUESTED. */
    @Async
    @EventListener
    public void onMintQuoteRequested(TraceMintQuoteRequestedEvent event) {
        emit(factory.buildMintQuoteRequested(event), event.getQuoteId());
    }

    /** US1 — emit MELT_QUOTE_REQUESTED. */
    @Async
    @EventListener
    public void onMeltQuoteRequested(TraceMeltQuoteRequestedEvent event) {
        emit(factory.buildMeltQuoteRequested(event), event.getQuoteId());
    }

    /**
     * Validate-then-publish. Invariant violations (including the ≤64 input/output
     * and ≤64 KB caps the SDK enforces) are logged and the single event dropped —
     * never truncated, never propagated (FR-013). Any other fault is swallowed
     * (FR-007).
     */
    private void emit(TransactionEvent event, String quoteId) {
        try {
            List<OperationInvariants.Violation> violations = OperationInvariants.validate(event);
            if (!violations.isEmpty()) {
                log.error("trace_event_invalid kind={} quote_id={} violations={}",
                        event.kind(), quoteId, violations);
                return;
            }
            publisher.publish(event);
        } catch (Exception e) {
            log.error("trace_publish_error kind={} quote_id={} error={}",
                    event.kind(), quoteId, e.getMessage());
        }
    }
}
