package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantDebitFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantIouFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003 T100 / FR-002 / FR-005 — drives the funding-gate logic in
 * {@code VoucherFundingResolverImpl} +
 * {@code VoucherQuoteJpaRepository#attachFundingAndAdvance} against a
 * live Postgres for the five spec scenarios:
 *
 * <ol>
 *   <li>No funding row, no accepted webhook ⇒ resolver returns empty
 *       (MintTask would reject with {@code funding_required}).</li>
 *   <li>Customer-paid funding pre-attached ⇒ resolver returns the row.</li>
 *   <li>Customer-paid via accepted webhook fallback ⇒ resolver lazily
 *       creates the funding row; second resolve sees it (idempotent).</li>
 *   <li>Merchant-debit funding pre-attached ⇒ resolver returns it.</li>
 *   <li>Merchant-IOU funding pre-attached ⇒ resolver returns it; the
 *       MERCHANT_IOU alert path is exercised when the discriminator
 *       lands.</li>
 * </ol>
 *
 * <p>End-to-end HTTP + signing is not exercised here because the
 * signing path requires the cashu-vault stack to be wired; that's
 * outside the spec-003 scope. The funding-gate code path that this
 * IT exercises is the exact one MintTask invokes via the
 * MintIntegrityContext service locator.
 */
class VoucherQuoteDurableIT extends AbstractVoucherDurableIT {

    @Autowired
    VoucherFundingResolver resolver;

    @Test
    @Transactional("mintTransactionManager")
    void unfundedQuote_noWebhook_resolverReturnsEmpty_fundingRequired() {
        // Scenario 1 — no funding row, no webhook ⇒ MintTask would reject
        // with funding_required.
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(
                "durable-no-funding", 1000L);
        voucherQuoteJpaRepository.save(quote);

        Optional<VoucherFunding> resolved = resolver.resolveForQuote(quote);

        assertThat(resolved).isEmpty();
        assertThat(voucherIssuanceJpaRepository.count())
                .as("no issuance row should exist for a rejected quote")
                .isZero();
    }

    @Test
    @Transactional("mintTransactionManager")
    void customerPayment_preAttachedFunding_resolverReturnsExisting() {
        // Scenario 2 — funding_id already set on the quote.
        CustomerPaymentFundingEntity funding = VoucherTestSupport.customerPaymentFunding(
                VoucherTestSupport.newFundingId(), 1000L, "phoenixd-it", "evt-pre-attached");
        voucherFundingJpaRepository.save(funding);

        VoucherQuoteEntity quote = VoucherTestSupport.fundedQuote(
                "durable-customer-pre", 1000L, funding.getFundingId());
        voucherQuoteJpaRepository.save(quote);

        Optional<VoucherFunding> resolved = resolver.resolveForQuote(quote);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().fundingId()).isEqualTo(funding.getFundingId());
        assertThat(resolved.get().fundingSource()).isEqualTo(VoucherFundingSource.CUSTOMER_PAYMENT);
        assertThat(resolved.get().amount()).isEqualTo(1000L);
    }

    @Test
    @Transactional("mintTransactionManager")
    void customerPayment_webhookFallback_lazilyCreatesFundingRow() {
        // Scenario 3 — UNFUNDED quote with no funding_id, but a matching
        // accepted webhook is in the durable table. Resolver must lazily
        // create a CustomerPaymentFunding row.
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(
                "durable-webhook-fallback", 1000L);
        voucherQuoteJpaRepository.save(quote);

        webhookEventJpaRepository.save(VoucherTestSupport.acceptedWebhookEvent(
                "phoenixd-it", "evt-webhook-fallback",
                "durable-webhook-fallback", 1000L));

        Optional<VoucherFunding> first = resolver.resolveForQuote(quote);
        assertThat(first).isPresent();
        assertThat(first.get().fundingSource()).isEqualTo(VoucherFundingSource.CUSTOMER_PAYMENT);
        assertThat(first.get().amount()).isEqualTo(1000L);

        long fundingCountAfterFirst = voucherFundingJpaRepository.count();

        Optional<VoucherFunding> second = resolver.resolveForQuote(quote);
        assertThat(second).isPresent();
        assertThat(second.get().fundingId())
                .as("second resolve must reuse the same funding row (idempotent on provider_event_id)")
                .isEqualTo(first.get().fundingId());
        assertThat(voucherFundingJpaRepository.count())
                .as("no duplicate funding row created on repeat resolve")
                .isEqualTo(fundingCountAfterFirst);
    }

    @Test
    @Transactional("mintTransactionManager")
    void merchantDebit_preAttachedFunding_resolverReturnsIt() {
        // Scenario 4 — merchant_debit funding pre-attached.
        MerchantDebitFundingEntity funding = VoucherTestSupport.merchantDebitFunding(
                VoucherTestSupport.newFundingId(), 2000L, "merchant-alice", "debit-pre-attached");
        voucherFundingJpaRepository.save(funding);

        VoucherQuoteEntity quote = VoucherTestSupport.fundedQuote(
                "durable-merchant-debit", 2000L, funding.getFundingId());
        quote.setMerchantId("merchant-alice");
        voucherQuoteJpaRepository.save(quote);

        Optional<VoucherFunding> resolved = resolver.resolveForQuote(quote);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().fundingSource()).isEqualTo(VoucherFundingSource.MERCHANT_DEBIT);
        assertThat(resolved.get().merchantId()).isEqualTo("merchant-alice");
    }

    @Test
    @Transactional("mintTransactionManager")
    void merchantIou_preAttachedFunding_resolverReturnsIt() {
        // Scenario 5 — merchant_iou funding pre-attached. Today the IOU
        // is created out-of-band (see VoucherDurabilityProperties.IouPolicy
        // Javadoc); the resolver still returns it and MintTask emits the
        // cashu_mint_voucher_iou_issued_total alert regardless of policy.
        MerchantIouFundingEntity funding = VoucherTestSupport.merchantIouFunding(
                VoucherTestSupport.newFundingId(), 3000L, "merchant-bob", "iou-pre-attached", "staging");
        voucherFundingJpaRepository.save(funding);

        VoucherQuoteEntity quote = VoucherTestSupport.fundedQuote(
                "durable-merchant-iou", 3000L, funding.getFundingId());
        quote.setMerchantId("merchant-bob");
        voucherQuoteJpaRepository.save(quote);

        Optional<VoucherFunding> resolved = resolver.resolveForQuote(quote);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().fundingSource()).isEqualTo(VoucherFundingSource.MERCHANT_IOU);
        assertThat(resolved.get().merchantId()).isEqualTo("merchant-bob");
        assertThat(resolved.get().policyProfile()).isEqualTo("staging");
    }

    @Test
    @Transactional("mintTransactionManager")
    void attachFundingAndAdvance_movesUnfundedToFunded_atomically() {
        // Spec 003 § VoucherQuoteJpaRepository#attachFundingAndAdvance: the
        // CAS UPDATE folds funding-attach + UNFUNDED → FUNDED into one row.
        CustomerPaymentFundingEntity funding = VoucherTestSupport.customerPaymentFunding(
                VoucherTestSupport.newFundingId(), 1500L, "phoenixd-it", "evt-cas");
        voucherFundingJpaRepository.save(funding);

        voucherQuoteJpaRepository.save(VoucherTestSupport.unfundedQuote(
                "durable-cas", 1500L));

        int updated = voucherQuoteJpaRepository.attachFundingAndAdvance(
                "durable-cas", funding.getFundingId());

        assertThat(updated).isEqualTo(1);

        VoucherQuoteEntity reloaded = voucherQuoteJpaRepository
                .findById("durable-cas").orElseThrow();
        assertThat(reloaded.lifecycleState()).isEqualTo(VoucherLifecycleState.FUNDED);
        assertThat(reloaded.fundingId()).isEqualTo(funding.getFundingId());

        // Second attempt on the now-FUNDED row must lose the CAS race.
        int secondAttempt = voucherQuoteJpaRepository.attachFundingAndAdvance(
                "durable-cas", funding.getFundingId());
        assertThat(secondAttempt)
                .as("CAS UNFUNDED→FUNDED is single-shot per quote")
                .isZero();
    }
}
