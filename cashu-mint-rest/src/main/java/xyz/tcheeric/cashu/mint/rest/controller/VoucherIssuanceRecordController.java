package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.util.QuoteRef;

import java.time.Instant;
import java.util.Optional;

/**
 * The mint's issuance record for one voucher quote, for the gateway that audits voucher issuances.
 *
 * <p>Answers which voucher the quote was bound to when it was created, and the hash of the
 * blinded outputs the mint signed under it ({@code OutputsHash}: SHA-256 over the outputs sorted by
 * amount, keyset id, then {@code B_}, each rendered {@code amount:keyset:B_hex\n}). A gateway that
 * recomputes {@code B_ = hash_to_curve(secret) + r*G} from a voucher's proofs and gets the same hash
 * knows those proofs are exactly the ones signed for this quote, and so for the bound voucher.
 *
 * <p>Read-only, and readable only by the issuance-reader service identity or an operator. The
 * hash reveals nothing spendable, but the binding links a voucher id to a payment.
 *
 * <ul>
 *   <li>{@code 200} with the record; {@code outputsHash} and {@code issuedAt} are null until the
 *       quote is minted, {@code voucherId} is null for a quote created without a binding.</li>
 *   <li>{@code 404} when no voucher quote has this id.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1/vouchers/quotes")
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherIssuanceRecordController {

    private final VoucherQuoteRepository voucherQuoteRepository;

    @GetMapping("/{quoteId}/issuance")
    public ResponseEntity<IssuanceRecordResponse> issuanceRecord(@PathVariable("quoteId") String quoteId) {
        if (quoteId == null || quoteId.isBlank() || quoteId.length() > 64) {
            return ResponseEntity.badRequest().build();
        }
        Optional<VoucherQuote> quote = voucherQuoteRepository.findById(quoteId);
        if (quote.isEmpty()) {
            log.info("voucher_issuance_record miss quote_ref={}", QuoteRef.of(quoteId));
            return ResponseEntity.notFound().build();
        }
        Optional<VoucherIssuance> issuance = issuanceOf(quoteId);
        log.info("voucher_issuance_record hit quote_ref={} bound={} issued={}", QuoteRef.of(quoteId),
                quote.get().voucherId() != null, issuance.isPresent());
        return ResponseEntity.ok(IssuanceRecordResponse.of(quote.get(), issuance.orElse(null)));
    }

    private static Optional<VoucherIssuance> issuanceOf(String quoteId) {
        VoucherIssuanceRepository issuances = MintIntegrityContext.voucherIssuanceRepository();
        return issuances == null ? Optional.empty() : issuances.findByVoucherQuoteId(quoteId);
    }

    /** The issuance record. Null fields are written as null, so a reader never guesses. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IssuanceRecordResponse(String quoteId,
                                         String voucherId,
                                         long faceValue,
                                         String unit,
                                         String lifecycleState,
                                         String outputsHash,
                                         Long originalTokenAmount,
                                         Instant issuedAt) {

        static IssuanceRecordResponse of(VoucherQuote quote, VoucherIssuance issuance) {
            return new IssuanceRecordResponse(quote.quoteId(), quote.voucherId(), quote.faceValue(), quote.unit(),
                    quote.lifecycleState().name(),
                    issuance == null ? null : issuance.outputsHash(),
                    quote.originalTokenAmount(),
                    issuance == null ? null : issuance.issuedAt());
        }
    }
}
