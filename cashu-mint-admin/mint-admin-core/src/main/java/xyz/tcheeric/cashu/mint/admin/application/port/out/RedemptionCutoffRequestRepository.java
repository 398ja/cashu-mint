package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence for keyset redemption cutoff requests and their audit trail (cashu-mint#551).
 */
public interface RedemptionCutoffRequestRepository {

    void create(CutoffRequest request);

    /**
     * Moves a request from one status to another, recording who and why.
     *
     * @return true when the request was in {@code expected} and is now in {@code next};
     *     false when another decision got there first
     */
    boolean transition(UUID requestId, Status expected, Status next, String decidedBy, Instant decidedAt,
                       String outcome);

    Optional<CutoffRequest> findById(UUID requestId);

    List<CutoffRequest> findByMintId(MintId mintId);

    void audit(AuditEntry entry);

    List<AuditEntry> findAudit();

    /** Where a request stands. Only APPROVED is delivered to the mint. */
    enum Status {
        PENDING_APPROVAL,
        REJECTED,
        APPROVED,
        APPLIED,
        FAILED
    }

    /** One requested cutoff. */
    record CutoffRequest(UUID requestId, MintId mintId, String keysetId, Instant redeemUntil, String reason,
                         String requestedBy, Instant requestedAt, Status status, String decidedBy,
                         Instant decidedAt, String outcome) {

        public CutoffRequest {
            Objects.requireNonNull(requestId, "request id must not be null");
            Objects.requireNonNull(mintId, "mint id must not be null");
            Objects.requireNonNull(keysetId, "keyset id must not be null");
            Objects.requireNonNull(redeemUntil, "redeem-until must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(requestedBy, "requested-by must not be null");
            Objects.requireNonNull(requestedAt, "requested-at must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** One step of a request, append-only. */
    record AuditEntry(UUID requestId, MintId mintId, String keysetId, String action, String actor,
                      Instant occurredAt, String detail) {

        public AuditEntry {
            Objects.requireNonNull(requestId, "request id must not be null");
            Objects.requireNonNull(mintId, "mint id must not be null");
            Objects.requireNonNull(keysetId, "keyset id must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(occurredAt, "occurred-at must not be null");
        }
    }
}
