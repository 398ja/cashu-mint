package xyz.tcheeric.cashu.mint.rest.controller.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoffRepository;
import xyz.tcheeric.cashu.mint.proto.service.KeysetRedemptionCutoffService;
import xyz.tcheeric.cashu.mint.proto.service.KeysetRedemptionCutoffService.CutoffRefusedException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

/**
 * Operator channel for per-keyset redemption cutoffs (cashu-mint#551).
 *
 * <p>{@code PUT /admin/keysets/{keysetId}/redemption-cutoff} with
 * {@code {"redeemUntil": "2026-12-03T00:00:00Z", "reason": "...", "setBy": "..."}} records the
 * cutoff; {@code GET /admin/keysets/redemption-cutoffs} lists them. The caller is mint-admin, which
 * owns the approval and the operator-facing audit trail; the mint keeps its own history of every
 * change in {@code keyset_redemption_cutoff_history}.
 *
 * <ul>
 *   <li>200: recorded, body is the cutoff.</li>
 *   <li>400: {@code redeemUntil} missing, malformed or not in the future.</li>
 *   <li>404: the mint holds no such keyset.</li>
 *   <li>409: the keyset is active (it still signs), or its cutoff already passed (final).</li>
 * </ul>
 *
 * <p>Under {@code /admin/**}, so HTTP Basic with role {@code ADMIN} (see {@code SecurityConfig}).
 * Present only with the JPA module, which is where cutoffs are stored.
 */
@Slf4j
@RestController
@ConditionalOnBean(KeysetRedemptionCutoffRepository.class)
@RequestMapping("/admin/keysets")
public class KeysetRedemptionCutoffAdminController {

  private final KeysetRedemptionCutoffService service;

  public KeysetRedemptionCutoffAdminController(
      final KeysetRedemptionCutoffRepository cutoffs, final MintLoadService mintLoadService) {
    this.service = new KeysetRedemptionCutoffService(cutoffs, mintLoadService, Clock.systemUTC());
  }

  @GetMapping("/redemption-cutoffs")
  public List<CutoffResponse> list() {
    return service.list().stream().map(CutoffResponse::of).toList();
  }

  @PutMapping("/{keysetId}/redemption-cutoff")
  public ResponseEntity<?> set(
      @PathVariable("keysetId") final String keysetId, @RequestBody final CutoffRequest body)
      throws CashuErrorException {
    final Instant redeemUntil;
    try {
      redeemUntil = body == null || body.redeemUntil() == null ? null : Instant.parse(body.redeemUntil());
    } catch (final DateTimeParseException e) {
      return error(HttpStatus.BAD_REQUEST, "invalid_redeem_until", "redeemUntil must be an ISO-8601 instant");
    }
    try {
      final KeysetRedemptionCutoff cutoff =
          service.set(keysetId, redeemUntil, body == null ? null : body.reason(),
              body == null ? null : body.setBy());
      log.warn("redemption_cutoff set keyset_id={} redeem_until={} set_by={}",
          keysetId, cutoff.redeemUntil(), cutoff.setBy());
      return ResponseEntity.ok(CutoffResponse.of(cutoff));
    } catch (final CutoffRefusedException refused) {
      log.warn("redemption_cutoff refused_to_set keyset_id={} reason={}", keysetId, refused.refusal());
      return switch (refused.refusal()) {
        case UNKNOWN_KEYSET -> error(HttpStatus.NOT_FOUND, "keyset_not_found", refused.getMessage());
        case ACTIVE_KEYSET -> error(HttpStatus.CONFLICT, "keyset_active", refused.getMessage());
        case ALREADY_RETIRED -> error(HttpStatus.CONFLICT, "cutoff_passed", refused.getMessage());
        case NOT_IN_FUTURE -> error(HttpStatus.BAD_REQUEST, "invalid_redeem_until", refused.getMessage());
      };
    }
  }

  private static ResponseEntity<Map<String, String>> error(
      final HttpStatus status, final String code, final String detail) {
    return ResponseEntity.status(status).body(Map.of("code", code, "detail", detail));
  }

  /** Request body: {@code redeemUntil} is an ISO-8601 instant. */
  public record CutoffRequest(String redeemUntil, String reason, String setBy) {}

  /** One cutoff as the admin reads it back. */
  public record CutoffResponse(
      String keysetId, String redeemUntil, boolean passed, String reason, String setBy, String setAt) {

    static CutoffResponse of(final KeysetRedemptionCutoff cutoff) {
      return new CutoffResponse(cutoff.keysetId(), cutoff.redeemUntil().toString(),
          cutoff.hasPassed(Instant.now()), cutoff.reason(), cutoff.setBy(),
          cutoff.setAt() == null ? null : cutoff.setAt().toString());
    }
  }
}
