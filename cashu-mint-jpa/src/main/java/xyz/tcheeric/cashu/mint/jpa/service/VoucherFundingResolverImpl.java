package xyz.tcheeric.cashu.mint.jpa.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherFundingJpaRepository;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;

import java.util.Optional;
import java.util.UUID;

/**
 * Spec 003 — default resolver implementation. Strategy:
 *
 * <ol>
 *   <li>If {@code quote.fundingId} is already set, return that funding row
 *       (the webhook bridge or operator already attached it).</li>
 *   <li>Otherwise, fall back to scanning the spec-001 {@code webhook_event}
 *       table for an {@code accepted} event matching the quote_id; if
 *       found, create a {@link CustomerPaymentFundingEntity} lazily, save
 *       it, and return it. This is the safety net for the race where the
 *       webhook bridge has not yet committed.</li>
 * </ol>
 *
 * <p>{@link VoucherFundingSource#MERCHANT_DEBIT} resolution requires an
 * external merchant-ledger client which is out of scope for v1; the
 * funding row is created out-of-band by the merchant-ledger API path
 * and {@link #resolveForQuote} just returns the row that was already
 * attached.
 *
 * <p>{@link VoucherFundingSource#MERCHANT_IOU} is handled the same way
 * — the operator endpoint creates the row out-of-band. This resolver
 * does not synthesize IOU rows. Every IOU issuance emits an alert
 * (FR-014) regardless of policy; that signal is wired by
 * {@code MintTask}'s voucher branch when it observes an IOU funding
 * row at issuance time.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherFundingResolverImpl implements VoucherFundingResolver {

    private final VoucherFundingJpaRepository fundingJpa;
    private final WebhookEventJpaRepository webhookJpa;

    @Override
    public Optional<VoucherFunding> resolveForQuote(VoucherQuote quote) {
        if (quote == null) {
            return Optional.empty();
        }

        if (quote.fundingId() != null) {
            return fundingJpa.findById(quote.fundingId()).map(e -> (VoucherFunding) e);
        }

        return webhookJpa.findFirstAcceptedByQuoteId(quote.quoteId())
                .map(event -> lazyCreateCustomerPaymentFunding(quote, event.provider(),
                        event.providerEventId(), event.amount(), event.unit()))
                .map(VoucherFunding.class::cast);
    }

    private CustomerPaymentFundingEntity lazyCreateCustomerPaymentFunding(
            VoucherQuote quote,
            String provider,
            String providerEventId,
            long amount,
            String unit) {

        Optional<CustomerPaymentFundingEntity> existing =
                fundingJpa.findByProviderEventId(provider, providerEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        CustomerPaymentFundingEntity funding = new CustomerPaymentFundingEntity();
        funding.setFundingId(UUID.randomUUID().toString());
        funding.setAmount(amount);
        funding.setUnit(unit);
        funding.setProvider(provider);
        funding.setProviderEventId(providerEventId);
        funding.setWebhookEventQuoteId(quote.quoteId());
        funding.setCustomerId(quote.customerId());

        try {
            CustomerPaymentFundingEntity saved = fundingJpa.save(funding);
            log.info("voucher_funding lazy_create funding_id={} quote_id={} provider={} provider_event_id={}",
                    saved.getFundingId(), quote.quoteId(), provider, providerEventId);
            MetricRecorders.voucher().lazyFundingCreated();
            return saved;
        } catch (DataIntegrityViolationException conflict) {
            // Spec 003 review fix — another writer (the webhook bridge,
            // or a concurrent mint call) inserted the same
            // (provider, provider_event_id) tuple between our find and
            // our save. The UNIQUE constraint catches it; re-read and
            // return the winner's row so the resolver stays idempotent
            // under concurrency.
            CustomerPaymentFundingEntity winner = fundingJpa
                    .findByProviderEventId(provider, providerEventId)
                    .orElseThrow(() -> conflict);
            log.info("voucher_funding lazy_create_race_lost funding_id={} quote_id={} provider={} provider_event_id={}",
                    winner.getFundingId(), quote.quoteId(), provider, providerEventId);
            return winner;
        }
    }
}
