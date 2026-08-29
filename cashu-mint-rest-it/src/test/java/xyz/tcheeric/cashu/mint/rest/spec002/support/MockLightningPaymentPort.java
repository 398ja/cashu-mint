package xyz.tcheeric.cashu.mint.rest.spec002.support;

import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spec 002 T002 / T003 — programmable test double for
 * {@link LightningPaymentPort} used by saga ITs.
 *
 * <p>Outcomes are scripted per call. The default if no script is set:
 * {@link PaymentOutcome.Unknown} so a test that forgot to wire the script
 * doesn't silently succeed.
 *
 * <p>Independent scripts for {@link #pay(String, Duration)} and
 * {@link #checkStatus(String)}: a saga IT can return {@code Unknown}
 * from {@code pay} then drive the reconciler with {@code Success} from
 * {@code checkStatus} to exercise the PAYMENT_UNKNOWN → COMPLETED
 * recovery path.
 *
 * <p>Thread-safe; supports concurrent calls from the saga reconciler.
 */
public final class MockLightningPaymentPort implements LightningPaymentPort {

    private final ConcurrentLinkedQueue<PaymentOutcome> payScript = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PaymentOutcome> statusScript = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, AtomicInteger> payCallsByQuote = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> statusCallsByQuote = new ConcurrentHashMap<>();
    private final List<String> payInvocations = new ArrayList<>();

    /** Script the next outcomes for {@link #pay}. */
    public MockLightningPaymentPort enqueuePay(PaymentOutcome... outcomes) {
        for (PaymentOutcome o : outcomes) payScript.add(o);
        return this;
    }

    /** Script the next outcomes for {@link #checkStatus}. */
    public MockLightningPaymentPort enqueueCheckStatus(PaymentOutcome... outcomes) {
        for (PaymentOutcome o : outcomes) statusScript.add(o);
        return this;
    }

    /** Reset the test-state between IT cases. */
    public void reset() {
        payScript.clear();
        statusScript.clear();
        payCallsByQuote.clear();
        statusCallsByQuote.clear();
        synchronized (payInvocations) {
            payInvocations.clear();
        }
    }

    public int payCallsFor(String quoteId) {
        AtomicInteger c = payCallsByQuote.get(quoteId);
        return c == null ? 0 : c.get();
    }

    public int statusCallsFor(String quoteId) {
        AtomicInteger c = statusCallsByQuote.get(quoteId);
        return c == null ? 0 : c.get();
    }

    public List<String> payInvocations() {
        synchronized (payInvocations) {
            return List.copyOf(payInvocations);
        }
    }

    @Override
    public PaymentOutcome pay(String quoteId, Duration timeout) {
        payCallsByQuote.computeIfAbsent(quoteId, k -> new AtomicInteger()).incrementAndGet();
        synchronized (payInvocations) {
            payInvocations.add(quoteId);
        }
        PaymentOutcome scripted = payScript.poll();
        return scripted != null ? scripted : new PaymentOutcome.Unknown("mock_default_unknown");
    }

    @Override
    public PaymentOutcome checkStatus(String quoteId) {
        statusCallsByQuote.computeIfAbsent(quoteId, k -> new AtomicInteger()).incrementAndGet();
        PaymentOutcome scripted = statusScript.poll();
        return scripted != null ? scripted : new PaymentOutcome.Unknown("mock_default_unknown");
    }
}
