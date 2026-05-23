package xyz.tcheeric.cashu.mint.jpa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuotePurgeLogEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuotePurgeLogJpaRepository;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec 004 T300 / FR-003 / FR-010 — daily retention purge job.
 * Nullifies {@code customer_id} and {@code merchant_id} on voucher
 * quote + funding rows that have reached a terminal lifecycle state
 * ({@code ISSUED} / {@code EXPIRED} / {@code FAILED}) more than
 * {@code cashu.mint.voucher.identity-retention} ago. Financial
 * columns are deliberately untouched — see {@code spec.md} §
 * Retention Scope § A-G.
 *
 * <h2>Live + Envers shadow</h2>
 * The same transaction updates the live row AND every {@code _aud}
 * revision (per research R9 — deliberate Envers immutability break
 * for identity columns only).
 *
 * <h2>Idempotency</h2>
 * The {@code AND (customer_id IS NOT NULL OR merchant_id IS NOT NULL)}
 * filter means a re-run on the same row is a no-op. A row purged on
 * day N is invisible to the day-N+1 query.
 *
 * <h2>Schedule</h2>
 * Daily at the cron expression in {@code cashu.mint.voucher.identity-purge-cron}
 * (default {@code 0 0 3 * * *} — 03:00 mint-local). Runs are recorded
 * in {@code voucher_quote_purge_log} so an operator can correlate
 * "row exists but identity is null" to a specific purge event
 * (FR-010 distinguishes anonymous from purged).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class VoucherIdentityRetentionPurgeService {

    /**
     * (live table, _aud table, identity columns to nullify). Quote tables
     * have a {@code lifecycle_state} predicate; funding tables don't
     * (their lifecycle is implicitly the quote's, joined via
     * {@code funding_id}).
     */
    private static final List<PurgeTarget> TARGETS = List.of(
            new PurgeTarget("voucher_quote", "voucher_quote_aud",
                    List.of("customer_id", "merchant_id"), true),
            new PurgeTarget("customer_payment_funding", "customer_payment_funding_aud",
                    List.of("customer_id"), false),
            new PurgeTarget("merchant_debit_funding", "merchant_debit_funding_aud",
                    List.of("merchant_id"), false),
            new PurgeTarget("merchant_iou_funding", "merchant_iou_funding_aud",
                    List.of("merchant_id"), false));

    private final VoucherQuotePurgeLogJpaRepository purgeLog;
    private final DataSource dataSource;
    private final Duration retention;

    public VoucherIdentityRetentionPurgeService(
            VoucherQuotePurgeLogJpaRepository purgeLog,
            @Qualifier("mintJpaDataSource") DataSource dataSource,
            @Value("${cashu.mint.voucher.identity-retention:PT2160H}") Duration retention) {
        this.purgeLog = purgeLog;
        this.dataSource = dataSource;
        this.retention = retention;
    }

    @Scheduled(cron = "${cashu.mint.voucher.identity-purge-cron:0 0 3 * * *}")
    public void scheduledPurge() {
        purgeOnce();
    }

    /**
     * Test-visible single-shot. Returns a snapshot of what happened
     * so ITs can assert without scraping logs.
     */
    @Transactional("mintTransactionManager")
    public PurgeResult purgeOnce() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant start = Instant.now();
        Instant cutoff = start.minus(retention);

        long liveRows = 0L;
        long audRows = 0L;
        for (PurgeTarget target : TARGETS) {
            Map<String, Long> counts = purgeTarget(jdbc, target, cutoff);
            liveRows += counts.get("live");
            audRows += counts.get("aud");
        }

        long durationMs = Duration.between(start, Instant.now()).toMillis();

        VoucherQuotePurgeLogEntity audit = new VoucherQuotePurgeLogEntity();
        audit.setPurgeId(UUID.randomUUID());
        audit.setPurgedAt(start);
        audit.setRetentionCutoff(cutoff);
        audit.setRowsPurged(liveRows);
        audit.setAudRowsPurged(audRows);
        audit.setDurationMs(durationMs);
        purgeLog.save(audit);

        log.info("voucher_identity_purge complete cutoff={} rows_purged={} aud_rows_purged={} duration_ms={}",
                cutoff, liveRows, audRows, durationMs);
        return new PurgeResult(audit.getPurgeId(), cutoff, liveRows, audRows, durationMs);
    }

    private Map<String, Long> purgeTarget(JdbcTemplate jdbc, PurgeTarget target, Instant cutoff) {
        String setClause = target.identityColumns().stream()
                .map(c -> c + " = NULL")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String identityFilter = target.identityColumns().stream()
                .map(c -> c + " IS NOT NULL")
                .reduce((a, b) -> a + " OR " + b)
                .orElseThrow();

        // Live table — only quote tables have lifecycle_state. Funding
        // tables purge whenever the linked voucher_quote.lifecycle_state
        // would qualify; for v1 the simpler approach is to purge funding
        // identity whenever any FK-linked voucher_quote is past retention.
        String liveSql;
        String audSql;
        if (target.hasLifecycle()) {
            liveSql = "UPDATE " + target.liveTable() + " SET " + setClause
                    + " WHERE lifecycle_state IN ('ISSUED', 'EXPIRED', 'FAILED')"
                    + " AND updated_at < ? AND (" + identityFilter + ")";
            audSql = "UPDATE " + target.audTable() + " SET " + setClause
                    + " WHERE updated_at < ? AND (" + identityFilter + ")";
        } else {
            liveSql = "UPDATE " + target.liveTable() + " SET " + setClause
                    + " WHERE funding_id IN ("
                    + "  SELECT funding_id FROM voucher_quote"
                    + "   WHERE lifecycle_state IN ('ISSUED', 'EXPIRED', 'FAILED')"
                    + "     AND updated_at < ?"
                    + " ) AND (" + identityFilter + ")";
            audSql = "UPDATE " + target.audTable() + " SET " + setClause
                    + " WHERE funding_id IN ("
                    + "  SELECT funding_id FROM voucher_quote"
                    + "   WHERE lifecycle_state IN ('ISSUED', 'EXPIRED', 'FAILED')"
                    + "     AND updated_at < ?"
                    + " ) AND (" + identityFilter + ")";
        }

        long live = jdbc.update(liveSql, java.sql.Timestamp.from(cutoff));
        long aud = jdbc.update(audSql, java.sql.Timestamp.from(cutoff));
        log.debug("voucher_identity_purge target={} live={} aud={}",
                target.liveTable(), live, aud);
        return Map.of("live", live, "aud", aud);
    }

    /** Per-table purge configuration. */
    private record PurgeTarget(String liveTable, String audTable,
                               List<String> identityColumns, boolean hasLifecycle) {}

    /** Snapshot returned to ITs. */
    public record PurgeResult(UUID purgeId, Instant retentionCutoff,
                              long rowsPurged, long audRowsPurged, long durationMs) {}
}
