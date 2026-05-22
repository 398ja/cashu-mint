package xyz.tcheeric.cashu.mint.webhook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEventRepository;
import xyz.tcheeric.cashu.mint.proto.service.PaymentStatusChecker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Receives payment webhooks and binds the {@code PENDING → PAID} transition to
 * the durable {@code mint_quote} row plus an append-only {@code webhook_event}
 * record keyed by {@code (provider, provider_event_id)} (spec 001 FR-005,
 * FR-006, FR-008).
 *
 * <p>The in-process Caffeine cache stays as a read-through accelerator for
 * {@link PaymentStatusChecker#isPaid} so {@code MintTask} can short-circuit
 * the gateway polling path. It is no longer the source of truth for write
 * decisions — those go through the JPA repositories when wired (production)
 * and fall back to the legacy cache-only path when the durable repos are
 * absent (unit-test contexts).
 */
@Slf4j
@Service
public class QuoteStatusUpdater implements PaymentStatusChecker {

    private static final String COUNTER_NAME = "cashu_mint_webhook_event_total";
    private static final int NOTIFICATION_BASE_SIZE = 120;

    private final Cache<String, PaymentNotification> paidQuotes;
    private final Cache<String, Boolean> processedNotifications;
    private final MintQuoteRepository mintQuoteRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookProperties webhookProperties;
    private final MeterRegistry meterRegistry;

    public QuoteStatusUpdater(
            @Value("${webhook.cache.quote-ttl:1h}") Duration quoteTtl,
            @Value("${webhook.cache.idempotency-ttl:24h}") Duration idempotencyTtl,
            @Value("${webhook.cache.max-quote-weight:10485760}") long maxQuoteWeight,
            @Value("${webhook.cache.max-idempotency-keys:100000}") int maxIdempotencyKeys,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Autowired(required = false) MintQuoteRepository mintQuoteRepository,
            @Autowired(required = false) WebhookEventRepository webhookEventRepository,
            @Autowired(required = false) WebhookProperties webhookProperties) {

        this.paidQuotes = Caffeine.newBuilder()
                .expireAfterWrite(quoteTtl)
                .maximumWeight(maxQuoteWeight)
                .weigher((String key, PaymentNotification value) -> estimateSize(key, value))
                .recordStats()
                .evictionListener((key, value, cause) ->
                        log.debug("Quote evicted from cache: key={}, cause={}", key, cause))
                .build();

        this.processedNotifications = Caffeine.newBuilder()
                .expireAfterWrite(idempotencyTtl)
                .maximumSize(maxIdempotencyKeys)
                .recordStats()
                .build();

        this.mintQuoteRepository = mintQuoteRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.webhookProperties = webhookProperties;
        this.meterRegistry = meterRegistry;

        if (meterRegistry != null) {
            CaffeineCacheMetrics.monitor(meterRegistry, paidQuotes, "webhook.paid_quotes");
            CaffeineCacheMetrics.monitor(meterRegistry, processedNotifications, "webhook.idempotency_keys");
            log.info("Cache metrics registered with Micrometer");
        }

        log.info("QuoteStatusUpdater initialized: durableMode={} quoteTtl={} maxQuoteWeight={} bytes",
                mintQuoteRepository != null && webhookEventRepository != null, quoteTtl, maxQuoteWeight);
    }

    /**
     * Spec 001 entry point: classifies an incoming webhook delivery against the
     * durable state. The full outcome matrix from data-model § WebhookEvent is
     * supported when the repositories are wired; in unit-test contexts (no
     * repos) the legacy cache-only path is used and the result is always either
     * {@link Outcome#accepted} or {@link Outcome#duplicate}.
     */
    public WebhookOutcome record(PaymentNotification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification must not be null");
        }

        if (mintQuoteRepository == null || webhookEventRepository == null) {
            return legacyMarkAsPaid(notification);
        }

        String provider = resolveProvider();
        String providerEventId = notification.getProviderEventId();
        long amount = toLong(notification.getAmount());

        // Idempotency: check (provider, provider_event_id) first to avoid PK
        // collision races.
        Optional<WebhookEvent> prior = webhookEventRepository.findById(provider, providerEventId);
        if (prior.isPresent()) {
            return classifyReplay(prior.get(), notification, amount, provider, providerEventId);
        }

        MintQuote quote = mintQuoteRepository.findById(notification.getQuoteId()).orElse(null);
        if (quote == null) {
            return persist(provider, providerEventId, notification, amount, Outcome.orphan);
        }

        if (amount != quote.amount()) {
            return persist(provider, providerEventId, notification, amount, Outcome.amount_mismatch);
        }
        if (!equalsCaseInsensitive(notification.getPaymentMethod(), quote.paymentMethod())) {
            return persist(provider, providerEventId, notification, amount, Outcome.method_mismatch);
        }
        // The webhook carries no explicit unit field; we treat it as matching
        // the durable quote's unit since the gateway is unit-scoped per
        // payment_method. If a future PaymentNotification carries unit, compare
        // here and emit unit_mismatch.

        if (quote.lifecycleState() != LifecycleState.UNPAID
                && quote.lifecycleState() != LifecycleState.PENDING) {
            Outcome o = (quote.lifecycleState() == LifecycleState.EXPIRED)
                    ? Outcome.expired
                    : Outcome.noop;
            return persist(provider, providerEventId, notification, amount, o);
        }

        // Move PENDING -> PAID (also accept UNPAID -> PAID for gateways that
        // don't emit a separate PENDING signal).
        int updated = mintQuoteRepository.casLifecycle(notification.getQuoteId(), LifecycleState.PENDING, LifecycleState.PAID);
        if (updated == 0) {
            updated = mintQuoteRepository.casLifecycle(notification.getQuoteId(), LifecycleState.UNPAID, LifecycleState.PAID);
        }
        if (updated == 0) {
            MintQuote refreshed = mintQuoteRepository.findById(notification.getQuoteId()).orElse(quote);
            Outcome o = (refreshed.lifecycleState() == LifecycleState.EXPIRED)
                    ? Outcome.expired
                    : Outcome.noop;
            return persist(provider, providerEventId, notification, amount, o);
        }

        WebhookOutcome result = persistAccepted(provider, providerEventId, notification, amount);
        if (result.isAccepted()) {
            paidQuotes.put(notification.getQuoteId(), notification);
        }
        return result;
    }

    private WebhookOutcome classifyReplay(WebhookEvent existing, PaymentNotification notification,
                                          long amount, String provider, String providerEventId) {
        boolean sameQuote = existing.quoteId().equals(notification.getQuoteId());
        boolean sameAmount = existing.amount() == amount;
        boolean sameMethod = equalsCaseInsensitive(existing.paymentMethod(), notification.getPaymentMethod());
        if (sameQuote && sameAmount && sameMethod) {
            incrementCounter(Outcome.duplicate);
            log.info("webhook_event duplicate provider={} provider_event_id={} quote_id={}",
                    provider, providerEventId, notification.getQuoteId());
            return WebhookOutcome.of(Outcome.duplicate);
        }
        incrementCounter(Outcome.tamper);
        log.warn("webhook_event tamper_signal provider={} provider_event_id={} stored_quote={} new_quote={} stored_amount={} new_amount={}",
                provider, providerEventId, existing.quoteId(), notification.getQuoteId(),
                existing.amount(), amount);
        return WebhookOutcome.of(Outcome.tamper);
    }

    private WebhookOutcome persist(String provider, String providerEventId,
                                   PaymentNotification notification, long amount, Outcome outcome) {
        try {
            webhookEventRepository.insert(new EventRow(
                    provider, providerEventId, notification.getQuoteId(), amount,
                    "sat", // see comment in record(); unit is inferred today
                    notification.getPaymentMethod(), null, outcome, Instant.now()));
        } catch (WebhookEventRepository.DuplicateEventException dup) {
            // Another writer beat us to it; surface as duplicate/tamper.
            return classifyReplay(dup.existing(), notification, amount, provider, providerEventId);
        }
        incrementCounter(outcome);
        log.warn("webhook_event outcome={} provider={} provider_event_id={} quote_id={} amount={}",
                outcome, provider, providerEventId, notification.getQuoteId(), amount);
        return WebhookOutcome.of(outcome);
    }

    private WebhookOutcome persistAccepted(String provider, String providerEventId,
                                           PaymentNotification notification, long amount) {
        try {
            webhookEventRepository.insert(new EventRow(
                    provider, providerEventId, notification.getQuoteId(), amount,
                    "sat", notification.getPaymentMethod(), null, Outcome.accepted, Instant.now()));
        } catch (WebhookEventRepository.DuplicateEventException dup) {
            return classifyReplay(dup.existing(), notification, amount, provider, providerEventId);
        }
        incrementCounter(Outcome.accepted);
        log.info("webhook_event accepted provider={} provider_event_id={} quote_id={} amount={}",
                provider, providerEventId, notification.getQuoteId(), amount);
        return WebhookOutcome.accepted();
    }

    private WebhookOutcome legacyMarkAsPaid(PaymentNotification notification) {
        @SuppressWarnings("deprecation")
        String idempotencyKey = notification.getIdempotencyKey();
        Boolean existing = processedNotifications.asMap().putIfAbsent(idempotencyKey, Boolean.TRUE);
        if (existing != null) {
            log.debug("Duplicate notification ignored: {}", idempotencyKey);
            return WebhookOutcome.of(Outcome.duplicate);
        }
        paidQuotes.put(notification.getQuoteId(), notification);
        log.info("Quote marked as paid via webhook: quoteId={}, method={}, amount={}",
                notification.getQuoteId(), notification.getPaymentMethod(), notification.getAmount());
        return WebhookOutcome.accepted();
    }

    private String resolveProvider() {
        if (webhookProperties != null) {
            String configured = webhookProperties.getProvider();
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        return "unknown";
    }

    private static boolean equalsCaseInsensitive(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static long toLong(Integer amount) {
        return amount == null ? 0L : amount.longValue();
    }

    private void incrementCounter(Outcome outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(COUNTER_NAME, "outcome", outcome.name()).increment();
    }

    /**
     * Legacy boolean entry point retained for source compatibility with callers
     * that still inspect {@code true}/{@code false}.
     *
     * @return {@code true} if the delivery is the first accepted notification
     *         for this idempotency key; {@code false} otherwise (any non-accepted
     *         outcome).
     */
    public boolean markAsPaid(PaymentNotification notification) {
        WebhookOutcome result = record(notification);
        return result.firstAccepted();
    }

    @Override
    public boolean isPaid(String quoteId) {
        return paidQuotes.getIfPresent(quoteId) != null;
    }

    public Optional<PaymentNotification> getPaymentDetails(String quoteId) {
        return Optional.ofNullable(paidQuotes.getIfPresent(quoteId));
    }

    @Override
    public Optional<String> getPreimage(String quoteId) {
        return getPaymentDetails(quoteId).map(PaymentNotification::getPreimage);
    }

    public PaymentNotification consumeQuote(String quoteId) {
        PaymentNotification removed = paidQuotes.asMap().remove(quoteId);
        if (removed != null) {
            log.debug("Quote consumed after minting: quoteId={}", quoteId);
        }
        return removed;
    }

    @Override
    public void markConsumed(String quoteId) {
        consumeQuote(quoteId);
    }

    public int getCacheSize() {
        paidQuotes.cleanUp();
        return (int) paidQuotes.estimatedSize();
    }

    public int getProcessedCount() {
        processedNotifications.cleanUp();
        return (int) processedNotifications.estimatedSize();
    }

    public void clear() {
        paidQuotes.invalidateAll();
        processedNotifications.invalidateAll();
        log.info("Quote status cache cleared");
    }

    public double getPaidQuotesHitRate() {
        return paidQuotes.stats().hitRate();
    }

    public double getIdempotencyHitRate() {
        return processedNotifications.stats().hitRate();
    }

    public long getPaidQuotesEvictionCount() {
        return paidQuotes.stats().evictionCount();
    }

    public void cleanUp() {
        paidQuotes.cleanUp();
        processedNotifications.cleanUp();
    }

    private int estimateSize(String key, PaymentNotification notification) {
        int size = NOTIFICATION_BASE_SIZE;
        size += key.length() * 2;
        if (notification.getQuoteId() != null) {
            size += notification.getQuoteId().length() * 2;
        }
        if (notification.getPaymentMethod() != null) {
            size += notification.getPaymentMethod().length() * 2;
        }
        if (notification.getPreimage() != null) {
            size += notification.getPreimage().length() * 2;
        }
        if (notification.getReceiptId() != null) {
            size += notification.getReceiptId().length() * 2;
        }
        return size;
    }

    @SuppressWarnings("unused")
    private static String digest(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Inline {@link WebhookEvent} carrier for the JPA adapter's insert call. */
    private record EventRow(
            String provider,
            String providerEventId,
            String quoteId,
            long amount,
            String unit,
            String paymentMethod,
            String signatureDigest,
            Outcome outcome,
            Instant receivedAt) implements WebhookEvent {
    }
}
