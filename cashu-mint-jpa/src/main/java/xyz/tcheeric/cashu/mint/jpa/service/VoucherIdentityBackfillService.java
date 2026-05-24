package xyz.tcheeric.cashu.mint.jpa.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdentityBackfillLogEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIdentityBackfillLogJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spec 004 T205 / FR-011 / Clarifications Q5 — boot-time backfill that
 * converts existing spec-003 raw npub identity columns into the
 * HMAC-SHA-256 form mandated by FR-002.
 *
 * <h2>Idempotency</h2>
 * Re-hashing an already-hashed value produces the same hash, so a
 * crash mid-batch is safe — the next boot picks up from
 * {@code voucher_identity_backfill_log.last_hashed_pk} and re-runs
 * the in-flight chunk. The SQL filter
 * {@code customer_id IS NOT NULL AND customer_id !~ '^[0-9a-f]{64}$'}
 * skips already-hashed rows so progress is naturally idempotent.
 *
 * <h2>Online — no table-level locks</h2>
 * Pagination keeps the working set small (1000 rows per transaction
 * by default); PostgreSQL row-level locking means concurrent writes
 * to non-conflicting rows continue uninterrupted.
 *
 * <h2>Atomic per-batch UPDATE pair (review fix)</h2>
 * Each batch invocation goes through {@link VoucherIdentityBackfillBatch}
 * — a separate bean — so the {@code @Transactional} boundary on the
 * batch executor is actually honoured by Spring's proxy. The live
 * table and Envers shadow UPDATEs commit together (per research R9).
 *
 * <h2>Dependency injection (review fix)</h2>
 * {@link IdentityHasher} is injected directly via the constructor
 * rather than read from {@code MintIntegrityContext.identityHasher()}.
 * Spring's DI guarantees the hasher bean is fully initialised before
 * this service is constructed — there is no init-order failure mode
 * where the backfill silently no-ops because the static context
 * hasn't been installed yet.
 *
 * <h2>Sequencing</h2>
 * Runs at {@code @PostConstruct} after the DB schema is in place
 * (Flyway runs before component initialisation). The
 * {@link xyz.tcheeric.cashu.mint.jpa.health.VoucherBackfillHealthIndicator}
 * keeps the readiness probe DOWN until this completes for every
 * required table.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class VoucherIdentityBackfillService {

    private final VoucherIdentityBackfillLogJpaRepository backfillLog;
    private final VoucherIdentityBackfillBatch batch;
    private final IdentityHasher hasher;
    private final int batchSize;

    /**
     * Map of live → list of identity columns. Backfilled in declared
     * order so the test fixture sees deterministic completion ordering.
     */
    private static final Map<String, List<String>> LIVE_TABLES = new LinkedHashMap<>();
    private static final Map<String, String> AUD_OF = new LinkedHashMap<>();
    static {
        LIVE_TABLES.put("voucher_quote", List.of("customer_id", "merchant_id"));
        LIVE_TABLES.put("customer_payment_funding", List.of("customer_id"));
        LIVE_TABLES.put("merchant_debit_funding", List.of("merchant_id"));
        LIVE_TABLES.put("merchant_iou_funding", List.of("merchant_id"));
        AUD_OF.put("voucher_quote", "voucher_quote_aud");
        AUD_OF.put("customer_payment_funding", "customer_payment_funding_aud");
        AUD_OF.put("merchant_debit_funding", "merchant_debit_funding_aud");
        AUD_OF.put("merchant_iou_funding", "merchant_iou_funding_aud");
    }

    public VoucherIdentityBackfillService(
            VoucherIdentityBackfillLogJpaRepository backfillLog,
            VoucherIdentityBackfillBatch batch,
            IdentityHasher hasher,
            @Value("${cashu.mint.voucher.identity-backfill-batch-size:1000}") int batchSize) {
        this.backfillLog = backfillLog;
        this.batch = batch;
        this.hasher = hasher;
        this.batchSize = batchSize;
    }

    @PostConstruct
    public void run() {
        log.info("voucher_identity_backfill start batch_size={}", batchSize);
        long totalRows = 0;
        long start = System.currentTimeMillis();
        for (Map.Entry<String, List<String>> entry : LIVE_TABLES.entrySet()) {
            String liveTable = entry.getKey();
            String audTable = AUD_OF.get(liveTable);
            for (String column : entry.getValue()) {
                totalRows += backfillColumn(liveTable, audTable, column);
            }
        }
        long duration = System.currentTimeMillis() - start;
        log.info("voucher_identity_backfill complete total_rows={} duration_ms={}", totalRows, duration);
    }

    /**
     * Backfill a single identity column across the live table and its
     * Envers shadow. Returns the number of rows hashed.
     */
    private long backfillColumn(String liveTable, String audTable, String column) {
        String tableKey = liveTable + "." + column;
        Optional<VoucherIdentityBackfillLogEntity> existingLog = backfillLog.findById(tableKey);
        if (existingLog.isPresent() && existingLog.get().getCompletedAt() != null) {
            log.debug("voucher_identity_backfill skip table={} (already complete)", tableKey);
            return 0L;
        }

        VoucherIdentityBackfillLogEntity logRow = existingLog.orElseGet(() -> {
            VoucherIdentityBackfillLogEntity fresh = new VoucherIdentityBackfillLogEntity();
            fresh.setTableName(tableKey);
            fresh.setStartedAt(Instant.now());
            fresh.setRowsHashed(0L);
            return fresh;
        });
        if (logRow.getStartedAt() == null) {
            logRow.setStartedAt(Instant.now());
        }

        long totalHashed = 0L;
        while (true) {
            // Goes through the Spring-proxied batch bean so the
            // @Transactional boundary on hashOneBatch is honoured.
            long batchHashed = batch.hashOneBatch(liveTable, audTable, column, hasher, batchSize);
            if (batchHashed == 0) {
                break;
            }
            totalHashed += batchHashed;
            logRow.setRowsHashed(logRow.getRowsHashed() + batchHashed);
            backfillLog.save(logRow);
            log.info("voucher_identity_backfill batch table={} rows={} cumulative={}",
                    tableKey, batchHashed, logRow.getRowsHashed());
        }

        logRow.setCompletedAt(Instant.now());
        backfillLog.save(logRow);
        log.info("voucher_identity_backfill complete table={} total_rows={}",
                tableKey, logRow.getRowsHashed());
        return totalHashed;
    }

    /**
     * Test-only seam — exposes the per-batch executor so
     * {@code BackfillResumeIT} can drive it directly. Production code
     * goes through {@link #run()}.
     */
    public long hashOneBatch(String liveTable, String audTable, String column, IdentityHasher h) {
        return batch.hashOneBatch(liveTable, audTable, column, h, batchSize);
    }
}
