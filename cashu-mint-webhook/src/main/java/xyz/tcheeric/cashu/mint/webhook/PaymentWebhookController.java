package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for receiving payment webhooks from payment-adapter.
 *
 * <p>This controller receives real-time payment notifications so the mint
 * can update quote status without polling the gateway.
 *
 * <p>Spec references (FR-014 — pinned-commit URLs tracked as a follow-up):
 * <ul>
 *   <li>NUT-04: <a href="https://github.com/cashubtc/nuts/blob/main/04.md">cashubtc/nuts §04</a> — mint tokens (payment side of the protocol)</li>
 * </ul>
 *
 * <p>Spec 001 contract (US2 / FR-005 / FR-006 / FR-007 / FR-008):
 * <ul>
 *   <li>Mandatory HMAC signature in non-{@code local} profiles
 *       (enforced at boot by {@link WebhookSecretStartupValidator}).</li>
 *   <li>Delivery is delegated to {@link QuoteStatusUpdater#record}, which
 *       resolves a {@link WebhookOutcome} against the durable
 *       {@code mint_quote} and {@code webhook_event} rows. The controller
 *       maps each outcome to an HTTP status — see
 *       {@code docs/how-to/configure-webhook-integrity.md} for the table.</li>
 * </ul>
 *
 * <p><b>Security:</b> Input validation and signature verification are performed
 * before processing any webhook notification.
 *
 * <p>Endpoint: POST /webhook/payment
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
public final class PaymentWebhookController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

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
     * <p>Spec 008 — the controller reads the <b>raw request body bytes</b> and
     * validates the HMAC signature over those exact bytes (the bytes the
     * adapter signed) BEFORE deserialising. Authenticating first avoids
     * parsing untrusted input and is what makes raw-body HMAC possible;
     * Jackson's {@code @RequestBody PaymentNotification} would otherwise
     * consume the stream and the original bytes would be lost.
     *
     * @param signature    HMAC signature from X-Webhook-Signature header
     * @param idempotencyKey idempotency key from X-Idempotency-Key header
     * @param rawBody       the exact request body bytes
     * @return 200 OK if processed, 400 if invalid input, 401 if signature invalid, 500 on error
     */
    @PostMapping("/payment")
    public ResponseEntity<WebhookResponse> handlePaymentWebhook(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) byte[] rawBody) {

        // A wholly-missing body is a client mistake (400), not an auth failure —
        // distinguish it from signature rejection (401) below.
        if (rawBody == null || rawBody.length == 0) {
            log.warn("Webhook received with empty body");
            return ResponseEntity.badRequest()
                    .body(WebhookResponse.error("Missing notification payload"));
        }

        // Authenticate over the exact bytes the sender signed, before parsing.
        if (!signatureValidator.validate(rawBody, signature)) {
            log.warn("Invalid webhook signature");
            return ResponseEntity.status(401)
                    .body(WebhookResponse.error("Invalid signature"));
        }

        // Deserialize the authenticated body. Log only the exception type — a
        // Jackson message can embed snippets of the (untrusted) payload, e.g. a
        // preimage; the full stack trace is available at DEBUG.
        PaymentNotification notification;
        try {
            notification = MAPPER.readValue(rawBody, PaymentNotification.class);
        } catch (Exception e) {
            log.warn("Webhook body could not be parsed: {}", e.getClass().getSimpleName());
            log.debug("Webhook body parse failure detail", e);
            return ResponseEntity.badRequest()
                    .body(WebhookResponse.error("Malformed notification payload"));
        }

        // Input validation (per Oracle Secure Coding Guidelines INPUT-1)
        if (notification == null) {
            log.warn("Webhook received with null notification");
            return ResponseEntity.badRequest()
                    .body(WebhookResponse.error("Missing notification payload"));
        }
        if (notification.getQuoteId() == null || notification.getQuoteId().isBlank()) {
            log.warn("Webhook received with missing quoteId");
            return ResponseEntity.badRequest()
                    .body(WebhookResponse.error("Missing quoteId"));
        }
        if (notification.getPaymentMethod() == null || notification.getPaymentMethod().isBlank()) {
            log.warn("Webhook received with missing paymentMethod");
            return ResponseEntity.badRequest()
                    .body(WebhookResponse.error("Missing paymentMethod"));
        }

        log.info("Received payment webhook: quoteId={}, method={}, idempotencyKey={}",
                notification.getQuoteId(), notification.getPaymentMethod(), idempotencyKey);

        try {
            WebhookOutcome outcome = quoteStatusUpdater.record(notification);
            return switch (outcome.outcome()) {
                case accepted -> {
                    log.info("Payment webhook processed: quoteId={}, amount={}",
                            notification.getQuoteId(), notification.getAmount());
                    yield ResponseEntity.ok(WebhookResponse.success("Payment recorded"));
                }
                case duplicate -> ResponseEntity.ok(WebhookResponse.success("Duplicate ignored"));
                case tamper -> ResponseEntity.status(409)
                        .body(WebhookResponse.error("Tamper signal recorded"));
                case amount_mismatch -> ResponseEntity.unprocessableEntity()
                        .body(WebhookResponse.error("Amount does not match quote"));
                case unit_mismatch -> ResponseEntity.unprocessableEntity()
                        .body(WebhookResponse.error("Unit does not match quote"));
                case method_mismatch -> ResponseEntity.unprocessableEntity()
                        .body(WebhookResponse.error("Payment method does not match quote"));
                case expired -> ResponseEntity.status(410)
                        .body(WebhookResponse.error("Quote has expired"));
                case noop -> ResponseEntity.ok(WebhookResponse.success("No state transition required"));
                case orphan -> ResponseEntity.status(202)
                        .body(WebhookResponse.success("Webhook stored; quote not yet known"));
                case unsigned_rejected, signature_invalid -> ResponseEntity.status(401)
                        .body(WebhookResponse.error("Signature rejected"));
            };
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
