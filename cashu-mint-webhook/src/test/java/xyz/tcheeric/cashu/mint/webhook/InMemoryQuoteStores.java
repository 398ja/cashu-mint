package xyz.tcheeric.cashu.mint.webhook;

import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEventRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-ins for the durable quote, webhook, funding and issuance stores, with the same
 * compare-and-set semantics as the JPA adapters, so a test can drive a quote from creation through
 * payment to issuance without a database.
 */
final class InMemoryQuoteStores {

    final Mints mintQuotes = new Mints();
    final Vouchers voucherQuotes = new Vouchers();
    final Events webhookEvents = new Events();
    final Fundings fundings = new Fundings();
    final Issuances issuances = new Issuances();
    final VoucherIssuances voucherIssuances = new VoucherIssuances();
    final VoucherFundingResolver fundingResolver = this::resolveFunding;

    /** Binds an accepted customer payment to its voucher quote, as VoucherFundingResolverImpl does. */
    private Optional<VoucherFunding> resolveFunding(VoucherQuote quote) {
        if (quote.fundingId() != null) {
            return fundings.findById(quote.fundingId());
        }
        return webhookEvents.firstAccepted(quote.quoteId()).map(event -> fundings.save(new Funding(
                UUID.randomUUID().toString(), VoucherFundingSource.CUSTOMER_PAYMENT, event.amount(),
                event.unit(), Instant.now())));
    }

    static final class Mints implements MintQuoteRepository {
        private final Map<String, MintQuote> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<MintQuote> findById(String quoteId) {
            return Optional.ofNullable(rows.get(quoteId));
        }

        @Override
        public MintQuote save(MintQuote quote) {
            rows.put(quote.quoteId(), quote);
            return quote;
        }

        @Override
        public int casLifecycle(String quoteId, LifecycleState expected, LifecycleState target) {
            MintQuote current = rows.get(quoteId);
            if (current == null || current.lifecycleState() != expected) {
                return 0;
            }
            rows.put(quoteId, new MintRow(current.quoteId(), current.amount(), current.unit(), current.mintUrl(),
                    current.paymentMethod(), current.invoiceId(), target, current.requestHash(),
                    current.createdAt(), Instant.now(), current.pubkey()));
            return 1;
        }
    }

    static final class Vouchers implements VoucherQuoteRepository {
        private final Map<String, VoucherRow> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<VoucherQuote> findById(String quoteId) {
            return Optional.ofNullable(rows.get(quoteId));
        }

        @Override
        public VoucherQuote save(VoucherQuote quote) {
            rows.put(quote.quoteId(), new VoucherRow(quote.quoteId(), quote.faceValue(), quote.chargedAmount(),
                    quote.unit(), quote.fundingId(), quote.lifecycleState(), quote.pubkey()));
            return rows.get(quote.quoteId());
        }

        @Override
        public int casLifecycle(String quoteId, VoucherLifecycleState expected, VoucherLifecycleState target) {
            VoucherRow current = rows.get(quoteId);
            if (current == null || current.lifecycleState() != expected) {
                return 0;
            }
            rows.put(quoteId, current.withState(current.fundingId(), target));
            return 1;
        }

        @Override
        public int recordIssuance(String quoteId, long originalTokenAmount) {
            return casLifecycle(quoteId, VoucherLifecycleState.ISSUING, VoucherLifecycleState.ISSUED);
        }

        @Override
        public int attachFundingAndAdvance(String quoteId, String fundingId) {
            VoucherRow current = rows.get(quoteId);
            if (current == null || current.lifecycleState() != VoucherLifecycleState.UNFUNDED) {
                return 0;
            }
            rows.put(quoteId, current.withState(fundingId, VoucherLifecycleState.FUNDED));
            return 1;
        }

        @Override
        public Optional<VoucherQuote> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<VoucherQuote> findByCustomerIdHash(String customerIdHash) {
            return List.of();
        }

        @Override
        public List<VoucherQuote> findByMerchantIdHash(String merchantIdHash) {
            return List.of();
        }
    }

    static final class Events implements WebhookEventRepository {
        private final Map<String, WebhookEvent> rows = new ConcurrentHashMap<>();

        @Override
        public WebhookEvent insert(WebhookEvent event) throws DuplicateEventException {
            WebhookEvent prior = rows.putIfAbsent(key(event.provider(), event.providerEventId()), event);
            if (prior != null) {
                throw new DuplicateEventException(prior);
            }
            return event;
        }

        @Override
        public Optional<WebhookEvent> findById(String provider, String providerEventId) {
            return Optional.ofNullable(rows.get(key(provider, providerEventId)));
        }

        Optional<WebhookEvent> firstAccepted(String quoteId) {
            return rows.values().stream()
                    .filter(event -> event.quoteId().equals(quoteId))
                    .filter(event -> event.outcome() == WebhookEvent.Outcome.accepted)
                    .findFirst();
        }

        private static String key(String provider, String providerEventId) {
            return provider + "|" + providerEventId;
        }
    }

    static final class Fundings implements VoucherFundingRepository {
        private final Map<String, VoucherFunding> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<VoucherFunding> findById(String fundingId) {
            return Optional.ofNullable(rows.get(fundingId));
        }

        @Override
        public VoucherFunding save(VoucherFunding funding) {
            rows.put(funding.fundingId(), funding);
            return funding;
        }

        @Override
        public Optional<VoucherFunding> findByProviderEventId(String provider, String providerEventId) {
            return Optional.empty();
        }
    }

    static final class Issuances implements IssuanceRecordRepository {
        private final Map<String, IssuanceRecord> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<IssuanceRecord> findById(String quoteId) {
            return Optional.ofNullable(rows.get(quoteId));
        }

        @Override
        public InsertResult insertIfAbsent(IssuanceRecord record) {
            IssuanceRecord prior = rows.putIfAbsent(record.quoteId(), record);
            return prior == null ? InsertResult.newlyInserted(record) : InsertResult.existing(prior);
        }
    }

    static final class VoucherIssuances implements VoucherIssuanceRepository {
        private final Map<String, VoucherIssuance> rows = new ConcurrentHashMap<>();

        @Override
        public Optional<VoucherIssuance> findByVoucherQuoteId(String voucherQuoteId) {
            return Optional.ofNullable(rows.get(voucherQuoteId));
        }

        @Override
        public VoucherIssuance insertIfAbsent(VoucherIssuance row) {
            return rows.computeIfAbsent(row.voucherQuoteId(), id -> row);
        }
    }

    record MintRow(String quoteId, long amount, String unit, String mintUrl, String paymentMethod,
                   String invoiceId, LifecycleState lifecycleState, String requestHash, Instant createdAt,
                   Instant updatedAt, String pubkey) implements MintQuote {
    }

    record VoucherRow(String quoteId, long faceValue, long chargedAmount, String unit, String fundingId,
                      VoucherLifecycleState lifecycleState, String pubkey) implements VoucherQuote {

        VoucherRow withState(String newFundingId, VoucherLifecycleState newState) {
            return new VoucherRow(quoteId, faceValue, chargedAmount, unit, newFundingId, newState, pubkey);
        }

        @Override public String voucherType() { return "customer_paid"; }
        @Override public long fee() { return chargedAmount; }
        @Override public String merchantId() { return null; }
        @Override public String customerId() { return null; }
        @Override public String idempotencyKey() { return null; }
        @Override public String requestHash() { return "0".repeat(64); }
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public Instant updatedAt() { return Instant.now(); }
    }

    record Funding(String fundingId, VoucherFundingSource fundingSource, long amount, String unit,
                   Instant createdAt) implements VoucherFunding {
    }
}
