package xyz.tcheeric.cashu.mint.jpa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapResponseCacheJpaRepository;

import java.time.Instant;

/**
 * Deletes NUT-19 swap responses whose ttl has passed (issue #482).
 *
 * <p>Correctness does not depend on this sweep: a lookup already ignores an expired entry, so a
 * slow or failed purge never replays a response past the advertised ttl. What the sweep bounds is
 * the table, which otherwise gains a row for every swap the mint ever answered.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SwapResponseCachePurger {

    private final SwapResponseCacheJpaRepository repository;

    @Scheduled(fixedDelayString = "${cashu.mint.swap.response-cache-purge-interval:PT5M}")
    public void purgeExpired() {
        try {
            int removed = repository.deleteExpiredBefore(Instant.now());
            if (removed > 0) {
                log.info("swap_response_cache_purge removed={}", removed);
            }
        } catch (RuntimeException e) {
            // One bad tick must not stop the schedule: the next purge catches up.
            log.warn("swap_response_cache_purge failed", e);
        }
    }
}
