package xyz.tcheeric.cashu.mint.rest.spec004;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantDebitFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantIouFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T210 / SC-001 — drives the {@link IdentityHashConverter}
 * end-to-end. Creates voucher quotes + funding rows with known npubs,
 * then queries raw SQL to confirm the stored value matches
 * {@code HmacSha256(salt, npub)} — proving the converter actually
 * ran on the write path.
 */
class IdentityHashAtRestIT extends AbstractVoucherDurableIT {

    private static final String SALT = "9f8a2c1b7e4d6a3f0c5b9d8e7f6a4c2b1d8e9f7a3c5b2d4e6f1a8c0b9d7e5f3a";

    @PersistenceContext(unitName = "cashu-mint-jpa")
    EntityManager entityManager;

    @Autowired
    IdentityHasher hasher;

    @Test
    @Transactional("mintTransactionManager")
    void voucherQuoteIdentityColumnsAreHashedAtRest() {
        // Seed an UNFUNDED voucher quote with a raw npub on the entity;
        // the converter should hash on the way to the DB.
        String rawCustomerNpub = "npub1test-customer-" + UUID.randomUUID();
        String rawMerchantNpub = "npub1test-merchant-" + UUID.randomUUID();

        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(
                "ihar-quote-" + UUID.randomUUID(), 1000L);
        quote.setCustomerId(rawCustomerNpub);
        quote.setMerchantId(rawMerchantNpub);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        // Raw-SQL query: what's actually in the column?
        String storedCustomer = (String) entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :qid")
                .setParameter("qid", quote.getQuoteId())
                .getSingleResult();
        String storedMerchant = (String) entityManager.createNativeQuery(
                        "SELECT merchant_id FROM voucher_quote WHERE quote_id = :qid")
                .setParameter("qid", quote.getQuoteId())
                .getSingleResult();

        assertThat(storedCustomer).isEqualTo(expectedHash(rawCustomerNpub));
        assertThat(storedMerchant).isEqualTo(expectedHash(rawMerchantNpub));

        // Cross-check the wired hasher produces the same value.
        assertThat(storedCustomer).isEqualTo(hasher.hash(rawCustomerNpub));
    }

    @Test
    @Transactional("mintTransactionManager")
    void customerPaymentFundingCustomerIdIsHashed() {
        String rawNpub = "npub1cpf-" + UUID.randomUUID();
        CustomerPaymentFundingEntity f = VoucherTestSupport.customerPaymentFunding(
                VoucherTestSupport.newFundingId(), 1500L, "phoenixd-it", "evt-" + UUID.randomUUID());
        f.setCustomerId(rawNpub);
        voucherFundingJpaRepository.saveAndFlush(f);

        String stored = (String) entityManager.createNativeQuery(
                        "SELECT customer_id FROM customer_payment_funding WHERE funding_id = :id")
                .setParameter("id", f.getFundingId())
                .getSingleResult();
        assertThat(stored).isEqualTo(expectedHash(rawNpub));
    }

    @Test
    @Transactional("mintTransactionManager")
    void merchantDebitFundingMerchantIdIsHashed() {
        String rawNpub = "npub1mdf-" + UUID.randomUUID();
        MerchantDebitFundingEntity f = VoucherTestSupport.merchantDebitFunding(
                VoucherTestSupport.newFundingId(), 2000L, rawNpub, "debit-" + UUID.randomUUID());
        voucherFundingJpaRepository.saveAndFlush(f);

        String stored = (String) entityManager.createNativeQuery(
                        "SELECT merchant_id FROM merchant_debit_funding WHERE funding_id = :id")
                .setParameter("id", f.getFundingId())
                .getSingleResult();
        assertThat(stored).isEqualTo(expectedHash(rawNpub));
    }

    @Test
    @Transactional("mintTransactionManager")
    void merchantIouFundingMerchantIdIsHashed() {
        String rawNpub = "npub1mif-" + UUID.randomUUID();
        MerchantIouFundingEntity f = VoucherTestSupport.merchantIouFunding(
                VoucherTestSupport.newFundingId(), 3000L, rawNpub, "iou-" + UUID.randomUUID(), "staging");
        voucherFundingJpaRepository.saveAndFlush(f);

        String stored = (String) entityManager.createNativeQuery(
                        "SELECT merchant_id FROM merchant_iou_funding WHERE funding_id = :id")
                .setParameter("id", f.getFundingId())
                .getSingleResult();
        assertThat(stored).isEqualTo(expectedHash(rawNpub));
    }

    @Test
    @Transactional("mintTransactionManager")
    void nullCustomerIdShortCircuitsToNullInDb() {
        // FR-019 — anonymous purchase: setting customer_id to null
        // (or never setting it) MUST leave the column NULL, not a hash
        // of an empty string.
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(
                "ihar-anon-" + UUID.randomUUID(), 500L);
        // customerId already null from builder
        assertThat(quote.getCustomerId()).isNull();
        voucherQuoteJpaRepository.saveAndFlush(quote);

        Object stored = entityManager.createNativeQuery(
                        "SELECT customer_id FROM voucher_quote WHERE quote_id = :qid")
                .setParameter("qid", quote.getQuoteId())
                .getSingleResult();
        assertThat(stored).isNull();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private static String expectedHash(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
