package xyz.tcheeric.cashu.mint.rest.voucher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIdempotencyKeyRepository;

import java.time.Instant;

/**
 * Spec 003 T062 — scheduled TTL sweep for the
 * {@code voucher_idempotency_key} cache so the table doesn't grow
 * unbounded.
 *
 * <p>Runs every 10 minutes by default. Only active when the JPA
 * adapter is wired (no-op otherwise).
 */
@Slf4j
@Component
@ConditionalOnBean(VoucherIdempotencyKeyRepository.class)
@RequiredArgsConstructor
public class VoucherIdempotencyKeySweeper {

    private final VoucherIdempotencyKeyRepository repository;

    @Scheduled(fixedDelayString = "${cashu.mint.voucher.idempotency-sweep-interval-ms:600000}")
    public void sweepExpired() {
        try {
            int removed = repository.deleteExpiredBefore(Instant.now());
            if (removed > 0) {
                log.info("voucher_idempotency_key_sweep removed={}", removed);
            }
        } catch (RuntimeException e) {
            log.warn("voucher_idempotency_key_sweep failed: {}", e.getMessage());
        }
    }
}
