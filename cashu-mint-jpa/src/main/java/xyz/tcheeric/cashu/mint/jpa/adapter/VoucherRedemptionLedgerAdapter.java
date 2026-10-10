package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/**
 * The per-voucher redemption ledger on Postgres (cashu-mint#540).
 *
 * <p>Two tables. {@code voucher_redemption} holds one row per coupon: its cap and the running
 * total. {@code voucher_redemption_claim} holds one row per declared swap, keyed by the swap's
 * NUT-19 request fingerprint, with the sats it claimed, who declared it, the hold it was taken
 * under, and whether it still counts.
 *
 * <p>Each operation is one short transaction of its own, deliberately outside any surrounding
 * one: a claim must be visible to every other instance the moment it commits, and must not be
 * rolled back by a later failure in the swap. Each starts with {@code SELECT ... FOR UPDATE} on the
 * coupon's row, so every change to one coupon serialises across instances, and every decision is
 * made against the committed total.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class VoucherRedemptionLedgerAdapter implements VoucherRedemptionLedger {

    private static final String SEED = """
            INSERT INTO voucher_redemption (voucher_id, cap_sats, redeemed_sats)
            VALUES (?, ?, 0)
            ON CONFLICT (voucher_id) DO NOTHING
            """;

    private static final String LOCK_TOTAL =
            "SELECT redeemed_sats, cap_sats FROM voucher_redemption WHERE voucher_id = ? FOR UPDATE";

    private static final String LOCK_CLAIM = """
            SELECT voucher_id, sats, redeemer_digest, state
              FROM voucher_redemption_claim WHERE request_fingerprint = ? FOR UPDATE
            """;

    private static final String CLAIM_BY_FINGERPRINT =
            "SELECT voucher_id FROM voucher_redemption_claim WHERE request_fingerprint = ?";

    private static final String CLAIMED_UNDER_HOLD =
            "SELECT request_fingerprint FROM voucher_redemption_claim WHERE hold_id = ? AND state = 'CLAIMED'";

    private static final String ADD = """
            UPDATE voucher_redemption
               SET redeemed_sats = redeemed_sats + ?,
                   cap_sats      = COALESCE(cap_sats, ?),
                   overspent     = overspent OR (COALESCE(cap_sats, ?) IS NOT NULL
                                                 AND redeemed_sats + ? > COALESCE(cap_sats, ?)),
                   updated_at    = now()
             WHERE voucher_id = ?
            """;

    private static final String UPGRADE_CAP =
            "UPDATE voucher_redemption SET cap_sats = ?, updated_at = now() WHERE voucher_id = ? AND cap_sats IS NULL";

    private static final String SUBTRACT = """
            UPDATE voucher_redemption
               SET redeemed_sats = GREATEST(redeemed_sats - ?, 0), updated_at = now()
             WHERE voucher_id = ?
            """;

    private static final String UPSERT_CLAIM = """
            INSERT INTO voucher_redemption_claim
                   (request_fingerprint, voucher_id, sats, redeemer_digest, hold_id, state)
            VALUES (?, ?, ?, ?, ?, 'CLAIMED')
            ON CONFLICT (request_fingerprint) DO UPDATE
               SET voucher_id = EXCLUDED.voucher_id, sats = EXCLUDED.sats,
                   redeemer_digest = EXCLUDED.redeemer_digest, hold_id = EXCLUDED.hold_id,
                   state = 'CLAIMED', updated_at = now()
            """;

    private static final String MOVE_TO_HOLD =
            "UPDATE voucher_redemption_claim SET hold_id = ?, updated_at = now() WHERE request_fingerprint = ?";

    private static final String SET_STATE =
            "UPDATE voucher_redemption_claim SET state = ?, updated_at = now() WHERE request_fingerprint = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public VoucherRedemptionLedgerAdapter(@Qualifier("mintJpaDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Claim claim(ClaimRequest request) {
        if (request.sats() <= 0) {
            throw new IllegalArgumentException("claimed sats must be positive");
        }
        jdbc.update(SEED, request.voucherId(), request.capSats());
        return tx.execute(status -> claimLocked(request));
    }

    private Claim claimLocked(ClaimRequest request) {
        long[] total = lockTotal(request.voucherId());
        long redeemed = total[0];
        Long recordedCap = total[1] < 0 ? null : total[1];
        boolean capMismatch = recordedCap != null && request.capSats() != null
                && !recordedCap.equals(request.capSats());
        Long effectiveCap = recordedCap != null ? recordedCap : request.capSats();

        ClaimRow previous = lockClaim(request.requestFingerprint());
        if (previous != null && "CLAIMED".equals(previous.state())) {
            if (!previous.voucherId().equalsIgnoreCase(request.voucherId()) || previous.sats() != request.sats()) {
                return new Claim(ClaimOutcome.DECLARATION_MISMATCH, redeemed, effectiveCap, capMismatch);
            }
            // A retry of a request whose claim already stands: the earlier attempt signed nothing
            // (its hold is gone, or this request could not have taken the inputs), so it is the
            // same credit. Count it once, and move it to this attempt's hold.
            jdbc.update(MOVE_TO_HOLD, request.holdId(), request.requestFingerprint());
            fillUnknownCap(request, recordedCap);
            return new Claim(ClaimOutcome.REUSED, redeemed, effectiveCap, capMismatch);
        }

        if (capMismatch && request.refuseOverCap()) {
            warnOnCapMismatch(request.voucherId(), request.capSats(), recordedCap, "refused");
            return new Claim(ClaimOutcome.REFUSED_CAP_MISMATCH, redeemed, recordedCap, true);
        }
        if (request.refuseOverCap() && effectiveCap != null && redeemed + request.sats() > effectiveCap) {
            fillUnknownCap(request, recordedCap);
            return new Claim(ClaimOutcome.REFUSED_OVER_CAP, redeemed, effectiveCap, capMismatch);
        }
        if (capMismatch) {
            warnOnCapMismatch(request.voucherId(), request.capSats(), recordedCap, "recorded");
        }
        jdbc.update(ADD, request.sats(), request.capSats(), request.capSats(), request.sats(),
                request.capSats(), request.voucherId());
        jdbc.update(UPSERT_CLAIM, request.requestFingerprint(), request.voucherId(), request.sats(),
                request.redeemerDigest(), request.holdId());
        return new Claim(ClaimOutcome.ACCEPTED, redeemed + request.sats(), effectiveCap, capMismatch);
    }

    /** A coupon first seen without a stated cap takes the real one the first time it is stated. */
    private void fillUnknownCap(ClaimRequest request, Long recordedCap) {
        if (recordedCap == null && request.capSats() != null) {
            jdbc.update(UPGRADE_CAP, request.capSats(), request.voucherId());
        }
    }

    @Override
    public boolean giveBack(String requestFingerprint) {
        String voucherId = voucherOf(requestFingerprint);
        if (voucherId == null) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            lockTotal(voucherId);
            ClaimRow row = lockClaim(requestFingerprint);
            if (row == null || !"CLAIMED".equals(row.state())) {
                return false;
            }
            jdbc.update(SUBTRACT, row.sats(), row.voucherId());
            jdbc.update(SET_STATE, "GIVEN_BACK", requestFingerprint);
            return true;
        }));
    }

    @Override
    public int giveBackForHold(String holdId) {
        int given = 0;
        for (String fingerprint : jdbc.queryForList(CLAIMED_UNDER_HOLD, String.class, holdId)) {
            if (giveBack(fingerprint)) {
                given++;
            }
        }
        return given;
    }

    @Override
    public Release release(String requestFingerprint, String voucherId, String redeemerDigest, Long sats) {
        String recordedVoucher = voucherOf(requestFingerprint);
        if (recordedVoucher == null) {
            return new Release(ReleaseOutcome.NOT_FOUND, 0, 0);
        }
        return tx.execute(status -> {
            lockTotal(recordedVoucher);
            ClaimRow row = lockClaim(requestFingerprint);
            if (row == null) {
                return new Release(ReleaseOutcome.NOT_FOUND, 0, 0);
            }
            if (!row.voucherId().equalsIgnoreCase(voucherId)) {
                return new Release(ReleaseOutcome.OTHER_VOUCHER, 0, 0);
            }
            if (!Objects.equals(row.redeemerDigest(), redeemerDigest)) {
                return new Release(ReleaseOutcome.OTHER_REDEEMER, 0, 0);
            }
            if (sats != null && sats != row.sats()) {
                return new Release(ReleaseOutcome.NOT_THE_DECLARED_SATS, 0, row.sats());
            }
            if (!"CLAIMED".equals(row.state())) {
                return new Release(ReleaseOutcome.ALREADY_RELEASED, 0, row.sats());
            }
            jdbc.update(SUBTRACT, row.sats(), row.voucherId());
            jdbc.update(SET_STATE, "RELEASED", requestFingerprint);
            return new Release(ReleaseOutcome.RELEASED, row.sats(), row.sats());
        });
    }

    private String voucherOf(String requestFingerprint) {
        List<String> rows = jdbc.queryForList(CLAIM_BY_FINGERPRINT, String.class, requestFingerprint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long[] lockTotal(String voucherId) {
        List<long[]> rows = jdbc.query(LOCK_TOTAL, (rs, n) -> new long[] {
                rs.getLong(1), rs.getObject(2) == null ? -1L : rs.getLong(2)}, voucherId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("voucher_redemption row missing for a claimed voucher");
        }
        return rows.get(0);
    }

    private ClaimRow lockClaim(String requestFingerprint) {
        List<ClaimRow> rows = jdbc.query(LOCK_CLAIM, (rs, n) -> new ClaimRow(rs.getString(1), rs.getLong(2),
                rs.getString(3), rs.getString(4)), requestFingerprint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record ClaimRow(String voucherId, long sats, String redeemerDigest, String state) {
    }

    /**
     * A genuine coupon derives one cap from its signed terms, so two different caps for one voucher
     * id mean a forged or inconsistent blob. In enforce the claim is refused; in log it is counted
     * against the cap on record and reported.
     */
    private static void warnOnCapMismatch(String voucherId, Long presented, Long recorded, String outcome) {
        log.warn("[alert] voucher_cap_mismatch voucherId={} presented={} recorded={} outcome={}",
                voucherId.length() <= 8 ? voucherId : voucherId.substring(0, 8), presented, recorded, outcome);
    }
}
