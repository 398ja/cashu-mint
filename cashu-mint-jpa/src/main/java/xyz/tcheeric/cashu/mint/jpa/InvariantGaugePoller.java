package xyz.tcheeric.cashu.mint.jpa;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issue #344 / ADR 0002 — exports operational invariants as gauges derived
 * from the database, by running the operator queries recorded on the
 * repositories on a fixed schedule.
 *
 * <p>Invariants are <em>durations in database state</em>, not in-process
 * moments: a counter incremented on a CAS transition can neither express
 * "stuck for an hour" nor survive a restart with its standing count intact,
 * which is exactly the wrong failure mode for conditions that by design never
 * resolve themselves. A gauge re-derived from operator SQL survives restarts
 * and reduces the alert to a one-line threshold.
 *
 * <p>Currently exports:
 * <ul>
 *   <li>{@code cashu_mint_melt_stuck_payment_unknown} — melt sagas parked in
 *       {@code PAYMENT_UNKNOWN} past {@code payment-unknown-ttl}
 *       ({@link MeltSagaJpaRepository#countStuckPaymentUnknown(Instant)}).
 *       Non-zero means the Lightning payment may have left the mint while the
 *       proofs were never burned; nothing resolves it without a human.</li>
 *   <li>{@code cashu_mint_invariant_poll_failures_total} — polls that threw.
 *       Without it a failing query would park the gauge on a stale zero and
 *       silently disarm the alert; the companion alert rule watches this
 *       counter and the absence of the gauge series.</li>
 * </ul>
 *
 * <p><strong>Single-instance assumption.</strong> Every mint replica running
 * this poller would publish its own copy of each series, so two replicas
 * produce two conflicting series per gauge. Both compose files deploy exactly
 * one mint instance; running more would require leader election here. Do not
 * design around replicas until that changes.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class InvariantGaugePoller {

    static final String STUCK_PAYMENT_GAUGE = "cashu_mint_melt_stuck_payment_unknown";
    static final String POLL_FAILURES_COUNTER = "cashu_mint_invariant_poll_failures_total";

    private final MeltSagaJpaRepository meltSagas;
    private final Duration paymentUnknownTtl;
    private final AtomicLong stuckPaymentUnknown = new AtomicLong();
    private final Counter pollFailures;

    public InvariantGaugePoller(MeltSagaJpaRepository meltSagas,
                                MeterRegistry registry,
                                @Value("${cashu.mint.melt.payment-unknown-ttl:PT1H}") Duration paymentUnknownTtl) {
        this.meltSagas = meltSagas;
        this.paymentUnknownTtl = paymentUnknownTtl;
        // Registered eagerly so both series are scrapeable before the first
        // poll: a meter that only materialises once something breaks is
        // indistinguishable from a broken exporter on a dashboard.
        Gauge.builder(STUCK_PAYMENT_GAUGE, stuckPaymentUnknown, AtomicLong::doubleValue)
                .description("Melt sagas stuck in PAYMENT_UNKNOWN past cashu.mint.melt.payment-unknown-ttl "
                        + "(see MeltSagaJpaRepository#countStuckPaymentUnknown)")
                .register(registry);
        this.pollFailures = Counter.builder(POLL_FAILURES_COUNTER)
                .description("Invariant poll attempts that failed; a non-zero rate means the gauges are stale")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${cashu.mint.invariant.poll-interval:PT60S}")
    public void pollTick() {
        try {
            stuckPaymentUnknown.set(meltSagas.countStuckPaymentUnknown(Instant.now().minus(paymentUnknownTtl)));
        } catch (RuntimeException e) {
            // Hold the last known value rather than reporting a false zero —
            // a zero here would silently clear a firing alert. The counter is
            // what makes the staleness itself alertable.
            pollFailures.increment();
            log.warn("invariant_poll stuck_payment_unknown_failed cause={}", e.getMessage());
        }
    }
}
