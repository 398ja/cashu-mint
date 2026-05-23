package xyz.tcheeric.cashu.mint.rest.spec004;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T212 / SC-011 / FR-019 — anonymous voucher purchases produce
 * zero identity storage end-to-end. customer_id stays null in both the
 * voucher_quote table and the linked customer_payment_funding table;
 * the hash function short-circuits on null input so there's no
 * enumerable hash-of-empty placeholder.
 */
class AnonymousPurchaseIT extends AbstractVoucherDurableIT {

    @PersistenceContext(unitName = "cashu-mint-jpa")
    EntityManager entityManager;

    @Test
    @Transactional("mintTransactionManager")
    void anonymousVoucherProducesNullCustomerIdEndToEnd() {
        String quoteId = "anon-" + UUID.randomUUID();

        // Customer-paid voucher with NO customer_id provided.
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 1000L);
        assertThat(quote.getCustomerId()).as("anonymous quote starts with null customer_id").isNull();
        voucherQuoteJpaRepository.saveAndFlush(quote);

        // Funding row also anonymous.
        CustomerPaymentFundingEntity funding = VoucherTestSupport.customerPaymentFunding(
                VoucherTestSupport.newFundingId(), 1000L, "phoenixd-it", "evt-" + UUID.randomUUID());
        assertThat(funding.getCustomerId()).isNull();
        voucherFundingJpaRepository.saveAndFlush(funding);

        // Raw SQL verifies the columns are NULL, not a hash of "" or whitespace.
        Object quoteCustomer = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :qid")
                .setParameter("qid", quoteId)
                .getSingleResult();
        Object fundingCustomer = entityManager.createNativeQuery(
                        "SELECT customer_id FROM customer_payment_funding WHERE funding_id = :id")
                .setParameter("id", funding.getFundingId())
                .getSingleResult();

        assertThat(quoteCustomer).as("voucher_quote.customer_id is NULL for anonymous").isNull();
        assertThat(fundingCustomer).as("customer_payment_funding.customer_id is NULL for anonymous").isNull();
    }

    @Test
    @Transactional("mintTransactionManager")
    void whitespaceOnlyCustomerIdAlsoStoresAsNull() {
        // FR-002 null short-circuit: empty / whitespace inputs MUST NOT
        // produce a hash-of-empty placeholder (which would be enumerable).
        String quoteId = "ws-" + UUID.randomUUID();
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 500L);
        quote.setCustomerId("   "); // whitespace
        voucherQuoteJpaRepository.saveAndFlush(quote);

        Object stored = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :qid")
                .setParameter("qid", quoteId)
                .getSingleResult();
        assertThat(stored).as("whitespace-only customer_id stored as NULL").isNull();
    }
}
