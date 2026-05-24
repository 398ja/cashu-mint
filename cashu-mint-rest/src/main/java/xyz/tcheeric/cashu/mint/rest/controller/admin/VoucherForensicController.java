package xyz.tcheeric.cashu.mint.rest.controller.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec 004 T500 / FR-009 — operator-facing forensic lookup. The
 * operator submits a raw npub; the mint hashes it internally with the
 * configured salt and queries the matching voucher quotes. The salt
 * never leaves the mint's environment.
 *
 * <h2>Security</h2>
 * Mounted under {@code /admin/**} so it inherits the
 * {@code hasRole("ADMIN")} rule from {@code SecurityConfig}.
 *
 * <h2>Retention boundary</h2>
 * Rows whose identity has been purged (FR-003) do not match the
 * hash lookup — their {@code customer_id} column is null. This is
 * the expected behaviour: post-retention purchases are not
 * recoverable by customer npub. To distinguish "purged" from "never
 * happened", operators query the {@code voucher_quote_purge_log}
 * separately.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequestMapping("/admin/voucher/forensic")
@RequiredArgsConstructor
public class VoucherForensicController {

    private final VoucherQuoteRepository voucherQuoteRepository;
    private final IdentityHasher identityHasher;

    @PostMapping("/customer-purchases")
    public ResponseEntity<Map<String, Object>> customerPurchases(
            @RequestBody Map<String, String> body) {
        String rawNpub = body.get("customerNpub");
        if (rawNpub == null || rawNpub.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerNpub_required"));
        }
        String hashedNpub = identityHasher.hash(rawNpub);
        log.info("voucher_forensic customer_lookup hash={}", hashedNpub);

        List<VoucherQuote> matches = voucherQuoteRepository.findByCustomerIdHash(hashedNpub);
        return ResponseEntity.ok(Map.of(
                "customer_hash", hashedNpub,
                "match_count", matches.size(),
                "matches", matches.stream().map(VoucherForensicController::toRow).toList()));
    }

    @PostMapping("/merchant-purchases")
    public ResponseEntity<Map<String, Object>> merchantPurchases(
            @RequestBody Map<String, String> body) {
        String rawNpub = body.get("merchantNpub");
        if (rawNpub == null || rawNpub.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "merchantNpub_required"));
        }
        String hashedNpub = identityHasher.hash(rawNpub);
        log.info("voucher_forensic merchant_lookup hash={}", hashedNpub);

        List<VoucherQuote> matches = voucherQuoteRepository.findByMerchantIdHash(hashedNpub);
        return ResponseEntity.ok(Map.of(
                "merchant_hash", hashedNpub,
                "match_count", matches.size(),
                "matches", matches.stream().map(VoucherForensicController::toRow).toList()));
    }

    /**
     * Render a single VoucherQuote for the forensic response. Identity
     * columns are deliberately excluded from the row payload — the
     * operator already supplied the raw value, so re-echoing it adds
     * nothing. The {@code retention_state} field signals whether the
     * row is in-window or post-retention/anonymous.
     */
    private static Map<String, Object> toRow(VoucherQuote q) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("quote_id", q.quoteId());
        row.put("face_value", q.faceValue());
        row.put("charged_amount", q.chargedAmount());
        row.put("unit", q.unit());
        row.put("lifecycle_state", q.lifecycleState() == null ? null : q.lifecycleState().name());
        row.put("funding_id", q.fundingId() == null ? "" : q.fundingId());
        row.put("created_at", q.createdAt() == null ? "" : q.createdAt().toString());
        row.put("updated_at", q.updatedAt() == null ? "" : q.updatedAt().toString());
        row.put("retention_state",
                (q.customerId() != null || q.merchantId() != null) ? "in_window" : "anonymous_or_purged");
        return row;
    }
}
