package xyz.tcheeric.cashu.mint.jpa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import javax.sql.DataSource;
import java.util.List;

/**
 * Spec 004 review fix (Codex PR #324) — extracted from
 * {@link VoucherIdentityBackfillService} so the {@code @Transactional}
 * boundary on {@link #hashOneBatch} is honoured.
 *
 * <p>Spring proxies {@code @Transactional} methods via JDK or CGLIB
 * proxies; an internal {@code this.hashOneBatch(...)} call from
 * inside the same class bypasses the proxy and runs without a
 * transaction (each JDBC statement auto-commits separately). With the
 * batch executor in a sibling bean, Spring routes
 * {@code backfillBatch.hashOneBatch(...)} through the proxy and the
 * live-table + {@code _aud} UPDATE pair commits atomically.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class VoucherIdentityBackfillBatch {

    private final JdbcTemplate jdbcTemplate;

    public VoucherIdentityBackfillBatch(@Qualifier("mintJpaDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Hash one batch of raw (non-hashed) values atomically across the
     * live table and its {@code _aud} shadow. Returns rows updated in
     * the live table.
     *
     * <p>"Raw" detected as "value is not 64-char lowercase hex." The
     * regex match is anchored so an accidental 64-char raw value (an
     * npub-of-exactly-64-hex-chars) won't false-positive — npubs are
     * bech32-encoded and contain non-hex characters.
     */
    @Transactional("mintTransactionManager")
    public long hashOneBatch(String liveTable, String audTable, String column,
                             IdentityHasher hasher, int batchSize) {
        String selectSql = "SELECT DISTINCT " + column + " FROM " + liveTable
                + " WHERE " + column + " IS NOT NULL"
                + "   AND " + column + " !~ '^[0-9a-f]{64}$'"
                + " LIMIT " + batchSize;
        List<String> rawValues = jdbcTemplate.queryForList(selectSql, String.class);
        if (rawValues.isEmpty()) {
            return 0L;
        }

        long updated = 0L;
        for (String raw : rawValues) {
            String hashed = hasher.hash(raw);
            if (hashed == null) {
                continue; // shouldn't happen — we filtered NOT NULL above
            }
            updated += jdbcTemplate.update(
                    "UPDATE " + liveTable + " SET " + column + " = ? WHERE " + column + " = ?",
                    hashed, raw);
            jdbcTemplate.update(
                    "UPDATE " + audTable + " SET " + column + " = ? WHERE " + column + " = ?",
                    hashed, raw);
        }
        return updated;
    }
}
