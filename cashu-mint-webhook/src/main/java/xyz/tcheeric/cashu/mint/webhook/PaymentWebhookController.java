package xyz.tcheeric.cashu.mint.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for receiving payment webhooks from payment-adapter.
 *
 * <p>This controller receives real-time payment notifications so the mint
 * can update quote status without polling the gateway.
 *
 * <p>Endpoint: POST /webhook/payment
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
public class PaymentWebhookController {

    private final QuoteStatusUpdater quoteStatusUpdater;
    private final WebhookSignatureValidator signatureValidator;

    public PaymentWebhookController(QuoteStatusUpdater quoteStatusUpdater,
                                    WebhookSignatureValidator signatureValidator) {
        this.quoteStatusUpdater = quoteStatusUpdater;
        this.signatureValidator = signatureValidator;
    }

    /**
     * Receive payment notification from payment-adapter.
     *
     * @param signature    HMAC signature from X-Webhook-Signature header
     * @param idempotencyKey idempotency key from X-Idempotency-Key header
     * @param notification the payment notification payload
     * @return 200 OK if processed, 401 if signature invalid, 500 on error
     */
    @PostMapping("/payment")
    public ResponseEntity<WebhookResponse> handlePaymentWebhook(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PaymentNotification notification) {

        log.info("Received payment webhook: quoteId={}, method={}, idempotencyKey={}",
                notification.getQuoteId(), notification.getPaymentMethod(), idempotencyKey);

        // Validate signature if configured
        if (!signatureValidator.validate(notification, signature)) {
            log.warn("Invalid webhook signature for quoteId={}", notification.getQuoteId());
            return ResponseEntity.status(401)
                    .body(WebhookResponse.error("Invalid signature"));
        }

        try {
            boolean isNew = quoteStatusUpdater.markAsPaid(notification);

            if (isNew) {
                log.info("Payment webhook processed: quoteId={}, amount={}",
                        notification.getQuoteId(), notification.getAmount());
                return ResponseEntity.ok(WebhookResponse.success("Payment recorded"));
            } else {
                log.debug("Duplicate payment webhook ignored: quoteId={}",
                        notification.getQuoteId());
                return ResponseEntity.ok(WebhookResponse.success("Duplicate ignored"));
            }

        } catch (Exception e) {
            log.error("Failed to process payment webhook: quoteId={}",
                    notification.getQuoteId(), e);
            return ResponseEntity.internalServerError()
                    .body(WebhookResponse.error("Processing failed: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint for webhook receiver.
     *
     * @return 200 OK with status information
     */
    @GetMapping("/health")
    public ResponseEntity<WebhookHealthResponse> health() {
        return ResponseEntity.ok(new WebhookHealthResponse(
                "UP",
                signatureValidator.isEnabled(),
                quoteStatusUpdater.getCacheSize(),
                quoteStatusUpdater.getProcessedCount()
        ));
    }

    /**
     * Simple response wrapper for webhook operations.
     */
    public record WebhookResponse(String status, String message) {
        public static WebhookResponse success(String message) {
            return new WebhookResponse("success", message);
        }

        public static WebhookResponse error(String message) {
            return new WebhookResponse("error", message);
        }
    }

    /**
     * Health check response with cache statistics.
     */
    public record WebhookHealthResponse(
            String status,
            boolean signatureValidationEnabled,
            int cachedQuotes,
            int processedNotifications
    ) {}
}
