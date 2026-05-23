package xyz.tcheeric.cashu.mint.rest.spec003;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIssuanceEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003 T103 / FR-005 — given an issued voucher, the audit query
 * {@code voucher_issuance JOIN voucher_funding} returns the funding
 * source in one indexed hop.
 */
class VoucherAuditTraceIT extends AbstractVoucherDurableIT {

    @PersistenceContext(unitName = "cashu-mint-jpa")
    EntityManager entityManager;

    @Test
    @Transactional("mintTransactionManager")
    void auditQuery_returns_funding_source_in_one_join_for_each_variant() {
        // Seed three issued vouchers — one per funding variant — and confirm the
        // FR-005 audit query resolves the funding source for each in a single JOIN.

        // CUSTOMER_PAYMENT
        CustomerPaymentFundingEntity customer = VoucherTestSupport.customerPaymentFunding(
                VoucherTestSupport.newFundingId(), 1000L, "phoenixd-it", "evt-customer-1");
        voucherFundingJpaRepository.save(customer);

        VoucherQuoteEntity customerQuote = VoucherTestSupport.fundedQuote(
                "audit-customer", 1000L, customer.getFundingId());
        voucherQuoteJpaRepository.save(customerQuote);

        VoucherIssuanceEntity customerIssuance = VoucherTestSupport.voucherIssuance(
                "audit-customer", customer.getFundingId(), "ff".repeat(32));
        voucherIssuanceJpaRepository.save(customerIssuance);

        // MERCHANT_DEBIT
        var debit = VoucherTestSupport.merchantDebitFunding(
                VoucherTestSupport.newFundingId(), 2000L, "merchant-alice", "debit-42");
        voucherFundingJpaRepository.save(debit);
        voucherQuoteJpaRepository.save(VoucherTestSupport.fundedQuote(
                "audit-debit", 2000L, debit.getFundingId()));
        voucherIssuanceJpaRepository.save(VoucherTestSupport.voucherIssuance(
                "audit-debit", debit.getFundingId(), "ee".repeat(32)));

        // MERCHANT_IOU
        var iou = VoucherTestSupport.merchantIouFunding(
                VoucherTestSupport.newFundingId(), 3000L, "merchant-bob", "iou-7", "staging");
        voucherFundingJpaRepository.save(iou);
        voucherQuoteJpaRepository.save(VoucherTestSupport.fundedQuote(
                "audit-iou", 3000L, iou.getFundingId()));
        voucherIssuanceJpaRepository.save(VoucherTestSupport.voucherIssuance(
                "audit-iou", iou.getFundingId(), "dd".repeat(32)));

        // The FR-005 audit query: given a voucher quote id, resolve the funding
        // entity in one JOIN. fundingSource is populated via @PostLoad on the
        // entity so callers see the typed discriminator without a second hop.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createQuery(
                "SELECT i.voucherQuoteId, f, f.amount "
                        + "FROM VoucherIssuanceEntity i "
                        + "JOIN VoucherFundingEntity f ON f.fundingId = i.fundingId "
                        + "WHERE i.voucherQuoteId IN ('audit-customer', 'audit-debit', 'audit-iou') "
                        + "ORDER BY i.voucherQuoteId")
                .getResultList();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)[0]).isEqualTo("audit-customer");
        assertThat(((xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity) rows.get(0)[1]).fundingSource())
                .isEqualTo(VoucherFundingSource.CUSTOMER_PAYMENT);
        assertThat(rows.get(0)[2]).isEqualTo(1000L);

        assertThat(rows.get(1)[0]).isEqualTo("audit-debit");
        assertThat(((xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity) rows.get(1)[1]).fundingSource())
                .isEqualTo(VoucherFundingSource.MERCHANT_DEBIT);
        assertThat(rows.get(1)[2]).isEqualTo(2000L);

        assertThat(rows.get(2)[0]).isEqualTo("audit-iou");
        assertThat(((xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity) rows.get(2)[1]).fundingSource())
                .isEqualTo(VoucherFundingSource.MERCHANT_IOU);
        assertThat(rows.get(2)[2]).isEqualTo(3000L);
    }

    @Test
    @Transactional("mintTransactionManager")
    void scOneCheck_no_issued_voucher_has_null_funding_id() {
        // SC-001 reconciliation: zero rows means the daily invariant holds.
        CustomerPaymentFundingEntity funding = VoucherTestSupport.customerPaymentFunding(
                VoucherTestSupport.newFundingId(), 1000L, "phoenixd-it", "evt-sc1");
        voucherFundingJpaRepository.save(funding);

        VoucherQuoteEntity quote = VoucherTestSupport.fundedQuote(
                "sc1-quote", 1000L, funding.getFundingId());
        quote.setLifecycleState(xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState.ISSUED);
        voucherQuoteJpaRepository.save(quote);

        @SuppressWarnings("unchecked")
        List<String> orphans = entityManager.createQuery(
                "SELECT q.quoteId FROM VoucherQuoteEntity q "
                        + "WHERE q.lifecycleState = xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState.ISSUED "
                        + "AND q.fundingId IS NULL")
                .getResultList();
        assertThat(orphans).isEmpty();
    }
}
