package xyz.tcheeric.cashu.mint.admin.rest.controller;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.CutoffRequest;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageRedemptionCutoffInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageRedemptionCutoffInteractor.CutoffRequestException;
import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminOpenApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminServiceException;
import xyz.tcheeric.cashu.mint.admin.rest.service.OperatorIdentity;
import xyz.tcheeric.nap.spring.annotation.RequiresPermission;

/**
 * Requests and approvals for retiring an archived keyset for redemption (cashu-mint#551).
 *
 * <p>Two Operators are needed: one requests a dated cutoff, another approves it. Only then is it
 * delivered to the mint, which from that date refuses the keyset's proofs and stops advertising it.
 */
@Tag(name = "Admin Keyset Redemption Cutoffs",
    description = "Retire archived keysets for redemption on an announced date")
@SecurityRequirement(name = AdminOpenApiConfiguration.ADMIN_SESSION_SCHEME)
@RequiresPermission(AdminPermission.Keys.OPERATIONS_EXECUTE)
@RestController
@RequestMapping("/admin/operations/mints/{mintId}/redemption-cutoffs")
public class RedemptionCutoffAdminController {

    private final ManageRedemptionCutoffInteractor interactor;
    private final OperatorIdentity operatorIdentity;

    public RedemptionCutoffAdminController(final ManageRedemptionCutoffInteractor interactor,
                                           final OperatorIdentity operatorIdentity) {
        this.interactor = interactor;
        this.operatorIdentity = operatorIdentity;
    }

    @Operation(summary = "List redemption cutoff requests for a mint")
    @GetMapping
    public List<CutoffRequestResponse> list(@PathVariable("mintId") final String mintId) {
        return interactor.list(mintId(mintId)).stream().map(CutoffRequestResponse::of).toList();
    }

    @Operation(summary = "Request a redemption cutoff on an archived keyset",
        description = "Records a pending request. Another Operator must approve it before the mint is told.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Request recorded, pending approval"),
        @ApiResponse(responseCode = "400", description = "Missing fields, or the date gives less than 24h notice",
            content = @Content(schema = @Schema(implementation = AdminErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "The mint holds no such keyset",
            content = @Content(schema = @Schema(implementation = AdminErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "The keyset is active and still signs",
            content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<CutoffRequestResponse> request(@PathVariable("mintId") final String mintId,
                                                         @RequestBody final CutoffRequestBody body) {
        final CutoffRequest created = run(() -> interactor.request(mintId(mintId),
            body == null ? null : body.keysetId(), parse(body == null ? null : body.redeemUntil()),
            body == null ? null : body.reason(), operatorIdentity.currentOperatorId()));
        return ResponseEntity.ok(CutoffRequestResponse.of(created));
    }

    @Operation(summary = "Approve a pending redemption cutoff",
        description = "Must be a different Operator from the requester. Queues delivery to the mint.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Approved and queued for the mint"),
        @ApiResponse(responseCode = "403", description = "The approver requested it",
            content = @Content(schema = @Schema(implementation = AdminErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Not pending, the date passed, or the keyset signs again",
            content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{requestId}/approve")
    public CutoffRequestResponse approve(@PathVariable("mintId") final String mintId,
                                         @PathVariable("requestId") final String requestId) {
        return CutoffRequestResponse.of(run(() ->
            interactor.approve(requestId(mintId, requestId), operatorIdentity.currentOperatorId())));
    }

    @Operation(summary = "Reject a pending redemption cutoff")
    @PostMapping("/{requestId}/reject")
    public CutoffRequestResponse reject(@PathVariable("mintId") final String mintId,
                                        @PathVariable("requestId") final String requestId,
                                        @RequestBody(required = false) final RejectBody body) {
        return CutoffRequestResponse.of(run(() -> interactor.reject(requestId(mintId, requestId),
            operatorIdentity.currentOperatorId(), body == null ? null : body.reason())));
    }

    /** Scopes the request id to the mint in the path, so one mint's URL cannot decide another's. */
    private UUID requestId(final String mintId, final String requestId) {
        final UUID id = uuid(requestId, "request id");
        final MintId mint = mintId(mintId);
        final boolean belongs = interactor.list(mint).stream().anyMatch(r -> r.requestId().equals(id));
        if (!belongs) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "cutoff_request_not_found",
                "no cutoff request " + requestId + " for mint " + mintId);
        }
        return id;
    }

    private static MintId mintId(final String mintId) {
        try {
            return MintId.fromString(mintId);
        } catch (final IllegalArgumentException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_mint_id", e.getMessage());
        }
    }

    private static UUID uuid(final String value, final String name) {
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_request_id", name + " must be a UUID");
        }
    }

    private static Instant parse(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException e) {
            throw new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_redeem_until",
                "redeemUntil must be an ISO-8601 instant, e.g. 2026-12-03T00:00:00Z");
        }
    }

    private static CutoffRequest run(final java.util.function.Supplier<CutoffRequest> action) {
        try {
            return action.get();
        } catch (final CutoffRequestException e) {
            throw switch (e.kind()) {
                case INVALID -> new AdminServiceException(HttpStatus.BAD_REQUEST, "invalid_cutoff", e.getMessage());
                case NOT_FOUND -> new AdminServiceException(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
                case CONFLICT -> new AdminServiceException(HttpStatus.CONFLICT, "cutoff_conflict", e.getMessage());
                case FORBIDDEN -> new AdminServiceException(HttpStatus.FORBIDDEN, "approval_forbidden", e.getMessage());
            };
        } catch (final RuntimeException e) {
            if (e instanceof AdminServiceException) {
                throw e;
            }
            throw new AdminServiceException(HttpStatus.BAD_GATEWAY, "vault_unavailable",
                "the vault could not be read to check the keyset: " + e.getMessage());
        }
    }

    /** Request body for a new cutoff. */
    public record CutoffRequestBody(String keysetId, String redeemUntil, String reason) {
    }

    /** Request body for a rejection. */
    public record RejectBody(String reason) {
    }

    /** One cutoff request as the UI shows it. */
    public record CutoffRequestResponse(String requestId, String mintId, String keysetId, Instant redeemUntil,
                                        String reason, String requestedBy, Instant requestedAt, String status,
                                        String decidedBy, Instant decidedAt, String outcome) {

        static CutoffRequestResponse of(final CutoffRequest request) {
            return new CutoffRequestResponse(request.requestId().toString(), request.mintId().asString(),
                request.keysetId(), request.redeemUntil(), request.reason(), request.requestedBy(),
                request.requestedAt(), request.status().name(), request.decidedBy(), request.decidedAt(),
                request.outcome());
        }
    }
}
