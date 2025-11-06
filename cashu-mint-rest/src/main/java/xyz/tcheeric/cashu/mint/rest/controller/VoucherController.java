package xyz.tcheeric.cashu.mint.rest.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for voucher operations.
 *
 * <p>This controller exposes HTTP endpoints for issuing and querying gift card vouchers.
 * It follows the Cashu REST API conventions and integrates with the voucher service layer.
 *
 * <h3>Architecture</h3>
 * <p>This controller is only active when voucher functionality is enabled
 * ({@code voucher.enabled=true}). It provides a thin HTTP layer over the voucher
 * application services, handling:
 * <ul>
 *   <li>Request validation</li>
 *   <li>HTTP status code mapping</li>
 *   <li>Error handling and response formatting</li>
 *   <li>Logging and monitoring</li>
 * </ul>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li><b>POST /v1/vouchers</b> - Issue a new voucher</li>
 *   <li><b>GET /v1/vouchers/{voucherId}/status</b> - Query voucher status</li>
 * </ul>
 *
 * <h3>Security Considerations</h3>
 * <p>In production deployments, consider:
 * <ul>
 *   <li>Rate limiting to prevent voucher farming</li>
 *   <li>Authentication/authorization for issuance endpoint</li>
 *   <li>Audit logging for compliance</li>
 *   <li>Input validation and sanitization</li>
 * </ul>
 *
 * <h3>Model B Enforcement</h3>
 * <p>This controller issues vouchers that are <b>NOT redeemable at the mint</b>.
 * Vouchers can only be spent at the issuing merchant. The {@code ProofValidator}
 * in the protocol layer enforces this by rejecting voucher secrets in swap/melt operations.
 *
 * @see VoucherService
 * @see xyz.tcheeric.cashu.mint.rest.config.VoucherConfiguration
 */
@Slf4j
@RestController
@RequestMapping("/v1/vouchers")
@ConditionalOnBean(VoucherService.class)
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    /**
     * Issue a new voucher.
     *
     * <p>Creates a new gift card voucher with the specified parameters, signs it with
     * the mint's issuer key, and publishes it to the Nostr ledger (NIP-33).
     *
     * <h3>Request Body</h3>
     * <pre>
     * {
     *   "issuerId": "merchant123",
     *   "unit": "sat",
     *   "amount": 10000,
     *   "expiresInDays": 365,
     *   "memo": "Birthday gift card"
     * }
     * </pre>
     *
     * <h3>Response Body</h3>
     * <pre>
     * {
     *   "voucher": {
     *     "secret": { ... },
     *     "issuerSignature": "...",
     *     "issuerPublicKey": "..."
     *   },
     *   "token": "cashuA...",
     *   "voucherId": "uuid",
     *   "amount": 10000,
     *   "unit": "sat"
     * }
     * </pre>
     *
     * <h3>HTTP Status Codes</h3>
     * <ul>
     *   <li><b>200 OK</b> - Voucher issued successfully</li>
     *   <li><b>400 Bad Request</b> - Invalid request parameters</li>
     *   <li><b>500 Internal Server Error</b> - Server error (Nostr publish failed, etc.)</li>
     * </ul>
     *
     * @param request the voucher issuance parameters
     * @return ResponseEntity containing the issued voucher or error
     */
    @PostMapping
    public ResponseEntity<IssueVoucherResponse> issueVoucher(
            @RequestBody IssueVoucherRequest request
    ) {
        log.info("POST /v1/vouchers - Issuing voucher for issuer={}, unit={}, amount={}",
                request.getIssuerId(), request.getUnit(), request.getAmount());

        // Validate required fields
        if (request.getIssuerId() == null || request.getIssuerId().isBlank()) {
            log.warn("Voucher issuance rejected: missing issuerId");
            return ResponseEntity.badRequest().build();
        }

        if (request.getUnit() == null || request.getUnit().isBlank()) {
            log.warn("Voucher issuance rejected: missing unit");
            return ResponseEntity.badRequest().build();
        }

        if (request.getAmount() == null || request.getAmount() <= 0) {
            log.warn("Voucher issuance rejected: invalid amount={}", request.getAmount());
            return ResponseEntity.badRequest().build();
        }

        if (request.getExpiresInDays() != null && request.getExpiresInDays() <= 0) {
            log.warn("Voucher issuance rejected: invalid expiresInDays={}", request.getExpiresInDays());
            return ResponseEntity.badRequest().build();
        }

        try {
            // Issue the voucher
            IssueVoucherResponse response = voucherService.issue(request);

            log.info("Voucher issued successfully: voucherId={}, amount={} {}",
                    response.getVoucherId(), response.getAmount(), response.getUnit());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Voucher issuance rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Failed to issue voucher: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Query the status of a voucher.
     *
     * <p>Queries the Nostr ledger (NIP-33) to retrieve the current status of a voucher.
     * This allows merchants and wallets to check if a voucher has been redeemed,
     * revoked, or is still valid.
     *
     * <h3>Response Body</h3>
     * <pre>
     * {
     *   "voucherId": "uuid",
     *   "status": "ISSUED"
     * }
     * </pre>
     *
     * <h3>Possible Status Values</h3>
     * <ul>
     *   <li><b>ISSUED</b> - Voucher is active and can be redeemed</li>
     *   <li><b>REDEEMED</b> - Voucher has been spent</li>
     *   <li><b>REVOKED</b> - Voucher has been canceled by issuer</li>
     *   <li><b>EXPIRED</b> - Voucher expiration time has passed</li>
     * </ul>
     *
     * <h3>HTTP Status Codes</h3>
     * <ul>
     *   <li><b>200 OK</b> - Status retrieved successfully</li>
     *   <li><b>404 Not Found</b> - Voucher ID not found in ledger</li>
     *   <li><b>500 Internal Server Error</b> - Server error (Nostr query failed, etc.)</li>
     * </ul>
     *
     * @param voucherId the unique voucher identifier
     * @return ResponseEntity containing the voucher status or error
     */
    @GetMapping("/{voucherId}/status")
    public ResponseEntity<VoucherStatusResponse> getVoucherStatus(
            @PathVariable("voucherId") String voucherId
    ) {
        log.debug("GET /v1/vouchers/{}/status", voucherId);

        if (voucherId == null || voucherId.isBlank()) {
            log.warn("Voucher status query rejected: missing voucherId");
            return ResponseEntity.badRequest().build();
        }

        try {
            Optional<VoucherStatus> status = voucherService.queryStatus(voucherId);

            if (status.isEmpty()) {
                log.debug("Voucher not found: voucherId={}", voucherId);
                return ResponseEntity.notFound().build();
            }

            VoucherStatusResponse response = new VoucherStatusResponse(voucherId, status.get());

            log.debug("Voucher status retrieved: voucherId={}, status={}", voucherId, status.get());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to query voucher status for voucherId={}: {}",
                    voucherId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Response DTO for voucher status queries.
     */
    public record VoucherStatusResponse(
            String voucherId,
            VoucherStatus status
    ) {
    }

    /**
     * Exception handler for all voucher-related errors.
     *
     * <p>Catches any unhandled exceptions and returns a standardized error response.
     * This provides a consistent error format for clients.
     *
     * @param e the exception
     * @return ResponseEntity with error details
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("Unhandled voucher controller exception: {}", e.getMessage(), e);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "Internal server error",
                        "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                ));
    }
}
