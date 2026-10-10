package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionCap;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionDeclaration;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lets a redeemer give back a voucher redemption it declared, when the credit it stood for is
 * undone (cashu-mint#540 review §1.2a).
 *
 * <p>{@code POST /v1/voucher-redemptions/release} with the redeemer's token in
 * {@value VoucherRedemptionDeclaration#REDEEMER_HEADER} and
 * {@code {"voucher_id": ..., "request_fingerprint": <the swap's receipt>, "sats": <optional>}}.
 *
 * <ul>
 *   <li>200 {@code released}: the declaration was given back.</li>
 *   <li>200 {@code already_released}: given back before, nothing more to give. A release is
 *       idempotent, so a redeemer that lost the reply can repeat it.</li>
 *   <li>400: {@code sats} differs from what the swap declared, or a malformed body. A release is
 *       all of one declaration, never more, never a part.</li>
 *   <li>401: no trusted redeemer token.</li>
 *   <li>403: the swap was declared by another redeemer.</li>
 *   <li>404: no declared swap under that receipt, or one for another voucher.</li>
 * </ul>
 *
 * <p>Outside {@code /v1/vouchers/**}, which is admin-only: the redeemer token is this endpoint's
 * authentication, as it is for the declaration on {@code /v1/swap}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/voucher-redemptions")
@RequiredArgsConstructor
public class VoucherRedemptionReleaseController {

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private final VoucherRedemptionCap cap;

    @PostMapping("/release")
    public ResponseEntity<Map<String, Object>> release(@RequestBody ReleaseRequest body,
                                                       HttpServletRequest httpRequest) throws CashuErrorException {
        if (body == null || body.voucherId() == null || body.requestFingerprint() == null
                || !FINGERPRINT.matcher(body.requestFingerprint()).matches()
                || (body.sats() != null && body.sats() <= 0)) {
            return ResponseEntity.badRequest().body(Map.of("detail",
                    "voucher_id and a 64-hex request_fingerprint are required; sats, if given, is positive"));
        }
        VoucherRedemptionLedger.Release release = cap.release(
                httpRequest.getHeader(VoucherRedemptionDeclaration.REDEEMER_HEADER), body.voucherId(),
                body.requestFingerprint(), body.sats());
        HttpStatus status = switch (release.outcome()) {
            case RELEASED, ALREADY_RELEASED -> HttpStatus.OK;
            case NOT_THE_DECLARED_SATS -> HttpStatus.BAD_REQUEST;
            case OTHER_REDEEMER -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, OTHER_VOUCHER -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of(
                "outcome", release.outcome().name().toLowerCase(java.util.Locale.ROOT),
                "released_sats", release.releasedSats(),
                "declared_sats", release.declaredSats()));
    }

    /** An untrusted token: 401, with the NUT-00 shape the swap path uses. */
    @ExceptionHandler(CashuErrorException.class)
    public ResponseEntity<Map<String, Object>> refused(CashuErrorException e) {
        int status = e.hasErrorCode() ? e.getErrorCode().getHttpStatus() : 500;
        return ResponseEntity.status(status).body(Map.of("detail", String.valueOf(e.getMessage()),
                "code", e.hasErrorCode() ? e.getErrorCode().getCode() : 0));
    }

    /**
     * @param voucherId          the voucher the swap declared
     * @param requestFingerprint the receipt the declared swap answered with
     * @param sats               the sats to release, or null for the whole declaration
     */
    public record ReleaseRequest(@JsonProperty("voucher_id") String voucherId,
                                 @JsonProperty("request_fingerprint") String requestFingerprint,
                                 @JsonProperty("sats") Long sats) {
    }
}
