package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;

import javax.sql.DataSource;
import java.util.List;

/**
 * The per-voucher redemption ledger on Postgres (cashu-mint#540).
 *
 * <p>Each call is one or two auto-committed statements, deliberately outside any surrounding
 * transaction: the claim must be visible to every other instance the moment it is made, and must
 * not be rolled back by a later failure in the swap, which instead gives it back explicitly with
 * {@link #release} when nothing was signed.
 *
 * <p>The claim is a single conditional {@code UPDATE ... RETURNING}. Postgres takes the row lock
 * for the update, so two claims on one voucher serialise, and the second evaluates its condition
 * against the first's committed total. No read-then-write window exists.
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

    /** Adds the claim when allowed. Returns the new total, or no row when refused. */
    private static final String CLAIM = """
            UPDATE voucher_redemption
               SET redeemed_sats = redeemed_sats + ?,
                   overspent     = overspent OR (cap_sats IS NOT NULL AND redeemed_sats + ? > cap_sats),
                   updated_at    = now()
             WHERE voucher_id = ?
               AND (? = false OR cap_sats IS NULL OR redeemed_sats + ? <= cap_sats)
            RETURNING redeemed_sats, cap_sats
            """;

    private static final String READ = "SELECT redeemed_sats, cap_sats FROM voucher_redemption WHERE voucher_id = ?";

    private static final String RELEASE = """
            UPDATE voucher_redemption
               SET redeemed_sats = GREATEST(redeemed_sats - ?, 0), updated_at = now()
             WHERE voucher_id = ?
            """;

    private final JdbcTemplate jdbc;

    public VoucherRedemptionLedgerAdapter(@Qualifier("mintJpaDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Claim claim(String voucherId, Long capSats, long sats, boolean refuseOverCap) {
        if (sats <= 0) {
            throw new IllegalArgumentException("claimed sats must be positive");
        }
        jdbc.update(SEED, voucherId, capSats);
        List<long[]> claimed = jdbc.query(CLAIM, (rs, row) -> new long[] {
                rs.getLong(1), rs.getObject(2) == null ? -1L : rs.getLong(2)},
                sats, sats, voucherId, refuseOverCap, sats);
        if (!claimed.isEmpty()) {
            long[] after = claimed.get(0);
            warnOnCapMismatch(voucherId, capSats, after[1]);
            return new Claim(true, after[0], after[1] < 0 ? null : after[1]);
        }
        long[] now = jdbc.queryForObject(READ, (rs, row) -> new long[] {
                rs.getLong(1), rs.getObject(2) == null ? -1L : rs.getLong(2)}, voucherId);
        return new Claim(false, now[0], now[1] < 0 ? null : now[1]);
    }

    @Override
    public void release(String voucherId, long sats) {
        jdbc.update(RELEASE, sats, voucherId);
    }

    /**
     * A genuine coupon derives one cap from its signed terms, so two different caps for one voucher
     * id mean a forged or inconsistent blob. The first cap on record is kept: it is the one the
     * coupon was first redeemed under.
     */
    private static void warnOnCapMismatch(String voucherId, Long presented, long recorded) {
        if (presented != null && recorded >= 0 && presented != recorded) {
            log.warn("voucher_cap_mismatch voucherId={} presented={} recorded={}",
                    voucherId.length() <= 8 ? voucherId : voucherId.substring(0, 8), presented, recorded);
        }
    }
}
