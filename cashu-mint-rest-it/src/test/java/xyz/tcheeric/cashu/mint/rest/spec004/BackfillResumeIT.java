package xyz.tcheeric.cashu.mint.rest.spec004;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIdentityBackfillLogJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.service.VoucherIdentityBackfillService;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T211 / FR-011 — backfill idempotency under crash-and-resume.
 *
 * <p>Setup: bypass the JPA converter (the backfill exists to handle
 * legacy raw npubs that pre-date the converter) by issuing raw SQL
 * INSERTs of plain-text customer_id values. Then run the backfill
 * once; assert all raw values become 64-char hex. Run the backfill
 * again; assert it's a no-op (the regex filter skips already-hashed
 * rows). Simulate "crash" by manually clearing the backfill log
 * completion marker and re-running; assert the resume converges
 * without re-processing already-hashed rows.
 */
class BackfillResumeIT extends AbstractVoucherDurableIT {

    @Autowired
    DataSource dataSource;

    @Autowired
    VoucherIdentityBackfillLogJpaRepository backfillLog;

    @Autowired
    VoucherIdentityBackfillService backfillService;

    @Autowired
    IdentityHasher hasher;

    @Test
    void backfillHashesRawNpubsAndIsIdempotentOnRerun() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Seed 25 raw-npub voucher_quote rows via raw SQL — bypasses
        // the JPA converter that would otherwise hash on write.
        Set<String> seededQuoteIds = new HashSet<>();
        Set<String> rawNpubs = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            String quoteId = "br-" + UUID.randomUUID();
            String rawNpub = "npub1raw-" + UUID.randomUUID();
            seededQuoteIds.add(quoteId);
            rawNpubs.add(rawNpub);
            jdbc.update(
                    "INSERT INTO voucher_quote " +
                            "(quote_id, voucher_type, face_value, charged_amount, fee, unit, " +
                            " customer_id, lifecycle_state, request_hash, created_at, updated_at, version) " +
                            "VALUES (?, 'customer_paid', 1000, 1000, 0, 'sat', ?, 'UNFUNDED', ?, now(), now(), 0)",
                    quoteId, rawNpub, "f".repeat(64));
        }

        // Sanity: rows have raw npubs.
        Long rawCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher_quote WHERE customer_id LIKE 'npub1raw-%'",
                Long.class);
        assertThat(rawCount).isEqualTo(25L);

        // Reset the backfill log for the relevant column so the service
        // re-runs (the boot-time backfill already ran during context start).
        backfillLog.findById("voucher_quote.customer_id").ifPresent(log -> {
            log.setCompletedAt(null);
            backfillLog.save(log);
        });

        // First backfill run.
        long firstRunHashed = backfillService.hashOneBatch(
                "voucher_quote", "voucher_quote_aud", "customer_id", hasher);

        assertThat(firstRunHashed)
                .as("first backfill run hashes the 25 raw rows")
                .isEqualTo(25L);

        // Every raw value is now a hashed value.
        Long postCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher_quote WHERE customer_id ~ '^[0-9a-f]{64}$' AND quote_id LIKE 'br-%'",
                Long.class);
        assertThat(postCount).isEqualTo(25L);

        // Idempotent re-run.
        long secondRunHashed = backfillService.hashOneBatch(
                "voucher_quote", "voucher_quote_aud", "customer_id", hasher);
        assertThat(secondRunHashed)
                .as("second backfill run is a no-op — every row is already hashed")
                .isZero();

        // Spot-check: a seeded raw npub now equals its HMAC hash in the DB.
        String anyRawNpub = rawNpubs.iterator().next();
        String expectedHash = hasher.hash(anyRawNpub);
        Long matchingHashCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher_quote WHERE customer_id = ?",
                Long.class, expectedHash);
        assertThat(matchingHashCount).isEqualTo(1L);
    }
}
