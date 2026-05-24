package xyz.tcheeric.cashu.mint.rest.spec004;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.service.VoucherIdentityRetentionPurgeService;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T310 / SC-002 — retention purge nullifies identity columns
 * on rows whose terminal lifecycle state is older than the configured
 * window. Financial fields remain untouched.
 */
class RetentionPurgeIT extends AbstractVoucherDurableIT {

    @PersistenceContext(unitName = "cashu-mint-jpa")
    EntityManager entityManager;

    @Autowired
    VoucherIdentityRetentionPurgeService purgeService;

    @Test
    @Transactional("mintTransactionManager")
    void issuedRowOlderThanRetention_hasIdentityNullified_financialFieldsPreserved() {
        // Seed an ISSUED voucher_quote + linked customer_payment_funding
        // both with raw-but-hashed-by-converter customer_id values. Set
        // updated_at backdated past the retention window (90 days default
        // — we use 91 days to be safe).
        String quoteId = "purge-issued-" + UUID.randomUUID();
        String fundingId = VoucherTestSupport.newFundingId();

        CustomerPaymentFundingEntity funding = VoucherTestSupport.customerPaymentFunding(
                fundingId, 1000L, "phoenixd-it", "evt-" + UUID.randomUUID());
        funding.setCustomerId("npub1purge-funding-" + UUID.randomUUID());
        voucherFundingJpaRepository.saveAndFlush(funding);

        VoucherQuoteEntity quote = VoucherTestSupport.fundedQuote(quoteId, 1000L, fundingId);
        quote.setCustomerId("npub1purge-quote-" + UUID.randomUUID());
        quote.setMerchantId("npub1purge-merchant-" + UUID.randomUUID());
        quote.setLifecycleState(VoucherLifecycleState.ISSUED);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        // Backdate updated_at past retention via raw SQL (JPA wouldn't
        // let us set it past now via PreUpdate).
        Instant pastRetention = Instant.now().minus(91, ChronoUnit.DAYS);
        entityManager.createNativeQuery(
                        "UPDATE voucher_quote SET updated_at = :ts WHERE quote_id = :qid")
                .setParameter("ts", java.sql.Timestamp.from(pastRetention))
                .setParameter("qid", quoteId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // Sanity: identity is set, financial fields are set.
        Object preCustomer = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        Object preFace = entityManager.createNativeQuery(
                        "SELECT face_value FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        assertThat(preCustomer).isNotNull();
        assertThat(preFace).isEqualTo(1000L);

        // Run purge.
        VoucherIdentityRetentionPurgeService.PurgeResult result = purgeService.purgeOnce();

        // Identity nullified on live + _aud.
        Object postCustomer = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        Object postMerchant = entityManager.createNativeQuery(
                        "SELECT merchant_id FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        assertThat(postCustomer).as("voucher_quote.customer_id purged").isNull();
        assertThat(postMerchant).as("voucher_quote.merchant_id purged").isNull();

        // Funding row identity also purged (linked via funding_id).
        Object postFunding = entityManager.createNativeQuery(
                        "SELECT customer_id FROM customer_payment_funding WHERE funding_id = :f")
                .setParameter("f", fundingId).getSingleResult();
        assertThat(postFunding).as("customer_payment_funding.customer_id purged").isNull();

        // Financial fields untouched.
        Object postFace = entityManager.createNativeQuery(
                        "SELECT face_value FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        Object postState = entityManager.createNativeQuery(
                        "SELECT lifecycle_state FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        Object postFundingId = entityManager.createNativeQuery(
                        "SELECT funding_id FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        assertThat(postFace).isEqualTo(1000L);
        assertThat(postState).isEqualTo("ISSUED");
        assertThat(postFundingId).isEqualTo(fundingId);

        // Result snapshot.
        assertThat(result.rowsPurged()).isGreaterThanOrEqualTo(2L); // quote + funding row
        assertThat(result.retentionCutoff()).isBefore(Instant.now());
    }

    @Test
    @Transactional("mintTransactionManager")
    void issuedRowWithinRetention_isUntouched() {
        String quoteId = "fresh-issued-" + UUID.randomUUID();
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 500L);
        quote.setCustomerId("npub1fresh-" + UUID.randomUUID());
        quote.setLifecycleState(VoucherLifecycleState.ISSUED);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        purgeService.purgeOnce();

        Object stored = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        assertThat(stored).as("within-retention row keeps its (hashed) identity").isNotNull();
    }

    @Test
    @Transactional("mintTransactionManager")
    void purgeIsIdempotent_secondRunIsNoOp() {
        String quoteId = "idem-" + UUID.randomUUID();
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 500L);
        quote.setCustomerId("npub1idem-" + UUID.randomUUID());
        quote.setLifecycleState(VoucherLifecycleState.ISSUED);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        Instant pastRetention = Instant.now().minus(91, ChronoUnit.DAYS);
        entityManager.createNativeQuery(
                        "UPDATE voucher_quote SET updated_at = :ts WHERE quote_id = :qid")
                .setParameter("ts", java.sql.Timestamp.from(pastRetention))
                .setParameter("qid", quoteId)
                .executeUpdate();

        purgeService.purgeOnce();
        VoucherIdentityRetentionPurgeService.PurgeResult second = purgeService.purgeOnce();

        // Second run finds nothing eligible — the IS NOT NULL filter skips
        // the now-purged row. The audit log row from second run has
        // rows_purged == 0 (or whatever was eligible from OTHER rows
        // that became overdue between the two runs — in this IT, 0).
        // We don't assert the precise count because other ITs may leave
        // overdue rows; we just assert that THIS row's identity stays null.
        Object stillNull = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :q")
                .setParameter("q", quoteId).getSingleResult();
        assertThat(stillNull).isNull();
        assertThat(second.purgeId()).isNotNull();
    }
}
