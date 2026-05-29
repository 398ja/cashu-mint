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
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;

import java.util.Optional;

/**
 * Spec 035 — voucher provenance lookup for the wallet's partial-spend
 * display correction.
 *
 * <p>A cashu V4 voucher token's embedded {@code SignedVoucher.face_value}
 * is frozen at original issuance and does NOT reflect partial spends.
 * The wallet's receive-side correction is:
 *
 * <pre>{@code
 *   issuance_ratio = face_value / original_token_amount
 *   derived_face_value = round(current_token_amount * issuance_ratio)
 * }</pre>
 *
 * <p>The wallet has {@code face_value} (embedded) and
 * {@code current_token_amount} (sum of the proofs it just received) on
 * hand, but {@code original_token_amount} lives only on the mint side —
 * captured at voucher-quote issuance time by
 * {@link xyz.tcheeric.cashu.mint.proto.tasks.MintTask}'s call to
 * {@link VoucherQuoteRepository#recordIssuance(String, long)} (spec
 * V20260601_007 migration).
 *
 * <p>This endpoint exposes that value, plus the derived
 * {@code issuance_ratio}, keyed by {@code voucherId} (== {@code quoteId}
 * in the voucher_quote table). It is wired upstream from
 * {@code imani-gateway-customer}'s {@code /wallet/token/metadata} and
 * {@code /wallet/receive} handlers (see spec 035 PR 3).
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@code 200 OK} when the voucher_quote row exists. Body always
 *       includes {@code voucherId}, {@code faceValue}, {@code faceUnit},
 *       {@code lifecycleState}; {@code originalTokenAmount} and
 *       {@code issuanceRatio} are {@code null} for legacy rows issued
 *       before the V20260601_007 migration (the wallet falls back to
 *       embedded face_value in that case — spec-035 iter-6 frontend
 *       already handles null gracefully).</li>
 *   <li>{@code 404 Not Found} when the voucher_quote row is missing.</li>
 *   <li>{@code 400 Bad Request} when {@code voucherId} is blank.</li>
 * </ul>
 *
 * <p><b>Ratio policy.</b> {@code issuance_ratio} is computed strictly
 * as {@code face_value / original_token_amount}. NEVER from the current
 * proof sum — that would reconstruct the (wrong) frozen face_value. If
 * {@code original_token_amount} is {@code null} or {@code <= 0}, the
 * ratio is {@code null}.
 *
 * <p>The plural {@code /v1/vouchers} prefix matches the existing voucher
 * routes ({@code POST /v1/vouchers}, {@code GET /v1/vouchers/{id}/status}).
 *
 * @see VoucherQuoteRepository#recordIssuance(String, long)
 */
@Slf4j
@RestController
@RequestMapping("/v1/vouchers")
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherProvenanceController {

    private final VoucherQuoteRepository voucherQuoteRepository;

    @GetMapping("/{voucherId}/provenance")
    public ResponseEntity<ProvenanceResponse> getProvenance(
            @PathVariable("voucherId") String voucherId
    ) {
        if (voucherId == null || voucherId.isBlank()) {
            log.warn("voucher_provenance rejected: blank voucherId");
            return ResponseEntity.badRequest().build();
        }

        Optional<VoucherQuote> maybe = voucherQuoteRepository.findById(voucherId);
        if (maybe.isEmpty()) {
            log.debug("voucher_provenance miss: voucher_id_prefix={}", voucherIdPrefix(voucherId));
            return ResponseEntity.notFound().build();
        }

        VoucherQuote q = maybe.get();
        Double ratio = computeIssuanceRatio(q.faceValue(), q.originalTokenAmount());

        log.info("voucher_provenance hit voucher_id_prefix={} face_value={} original_token_amount={} issuance_ratio={}",
                voucherIdPrefix(voucherId),
                q.faceValue(),
                q.originalTokenAmount(),
                ratio);

        return ResponseEntity.ok(new ProvenanceResponse(
                q.quoteId(),
                q.faceValue(),
                q.unit(),
                q.originalTokenAmount(),
                ratio,
                q.lifecycleState().name()
        ));
    }

    /**
     * {@code issuance_ratio = face_value / original_token_amount},
     * computed as {@code double} to preserve precision for the wallet's
     * downstream {@code Math.round(token_amount * issuance_ratio)} step.
     * Returns {@code null} when the amount is null or non-positive — the
     * wallet treats null as "legacy / cannot derive" and falls back to
     * the embedded face_value path.
     */
    static Double computeIssuanceRatio(long faceValue, Long originalTokenAmount) {
        if (originalTokenAmount == null || originalTokenAmount <= 0L) {
            return null;
        }
        return (double) faceValue / (double) originalTokenAmount;
    }

    /**
     * Truncate the voucher_id for logs — the full value can correlate
     * receive events across the wallet ↔ mint boundary and we keep it
     * out of structured logs per the repo's privacy policy.
     */
    private static String voucherIdPrefix(String voucherId) {
        if (voucherId == null || voucherId.length() < 8) return "??";
        return voucherId.substring(0, 8) + "…";
    }

    /**
     * Response DTO. Field names are camelCase per repo convention;
     * Jackson default casing applies. {@code originalTokenAmount} and
     * {@code issuanceRatio} are nullable — the wallet's source-chain
     * resolution (spec 035 iter 2) already accepts null and falls back
     * to embedded face_value.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProvenanceResponse(
            String voucherId,
            long faceValue,
            String unit,
            Long originalTokenAmount,
            Double issuanceRatio,
            String lifecycleState
    ) {
    }
}
