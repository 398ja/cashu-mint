package xyz.tcheeric.cashu.mint.jpa.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdentityBackfillLogEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIdentityBackfillLogJpaRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 004 T206 / FR-011 — readiness gate that keeps the mint's
 * Actuator readiness probe DOWN until the
 * {@link xyz.tcheeric.cashu.mint.jpa.service.VoucherIdentityBackfillService}
 * has marked every required (table, column) pair complete.
 *
 * <p>This is the operational guarantee that no voucher endpoint serves
 * traffic before the hash backfill finishes. Even if the backfill
 * crashes and resumes, the readiness probe stays DOWN until the
 * resumed run completes.
 *
 * <p>Tied to {@code cashu.mint.jpa.enabled=true} so legacy / unit-test
 * contexts (where no backfill runs) don't see a perpetually-DOWN
 * health indicator.
 */
@Component("voucherIdentityBackfill")
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherBackfillHealthIndicator implements HealthIndicator {

    /** The (table.column) keys that MUST have completed_at set before readiness. */
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "voucher_quote.customer_id",
            "voucher_quote.merchant_id",
            "customer_payment_funding.customer_id",
            "merchant_debit_funding.merchant_id",
            "merchant_iou_funding.merchant_id");

    private final VoucherIdentityBackfillLogJpaRepository backfillLog;

    @Override
    public Health health() {
        List<VoucherIdentityBackfillLogEntity> rows = backfillLog.findAll();
        Set<String> completed = rows.stream()
                .filter(r -> r.getCompletedAt() != null)
                .map(VoucherIdentityBackfillLogEntity::getTableName)
                .collect(Collectors.toSet());

        Set<String> outstanding = REQUIRED_KEYS.stream()
                .filter(k -> !completed.contains(k))
                .collect(Collectors.toSet());

        if (outstanding.isEmpty()) {
            return Health.up()
                    .withDetail("backfill_complete_for", REQUIRED_KEYS)
                    .build();
        }
        return Health.down()
                .withDetail("backfill_outstanding_for", outstanding)
                .withDetail("hint",
                        "voucher endpoints will start serving once the boot-time "
                                + "identity backfill completes; see specs/004-voucher-data-minimisation/quickstart.md § 3")
                .build();
    }
}
