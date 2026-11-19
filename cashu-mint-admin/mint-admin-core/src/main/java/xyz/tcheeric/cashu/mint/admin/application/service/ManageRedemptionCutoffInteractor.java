package xyz.tcheeric.cashu.mint.admin.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort.VaultKeySet;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.AuditEntry;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.CutoffRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.Status;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Requests, approves and rejects per-keyset redemption cutoffs (cashu-mint#551).
 *
 * <p>Retiring a keyset for redemption is irreversible once the date passes and ends value for any
 * holder who has not moved it, so it carries its own four-eyes approval: the Operator who requests
 * a cutoff cannot approve it. Only an approved request reaches the outbox, which delivers it to
 * the mint; every step is appended to the cutoff audit trail.
 *
 * <p>The checks here are the admin's best knowledge (the vault says the keyset is archived, the
 * date leaves at least {@link #MINIMUM_NOTICE} for holders). The mint checks again on delivery,
 * because it, not the admin, knows which keyset it signs with.
 */
public class ManageRedemptionCutoffInteractor {

    /** Outbox event that carries an approved cutoff to the mint. */
    public static final String CUTOFF_APPROVED_EVENT = "KEYSET_REDEMPTION_CUTOFF_APPROVED";

    /** Outbox attribute naming the request the message delivers. */
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    /**
     * The least warning a holder gets. A cutoff is an announced deadline, never a switch: the
     * runbook proposes 14 days on staging, and nothing shorter than a day is a window at all.
     */
    public static final Duration MINIMUM_NOTICE = Duration.ofHours(24);

    private static final String AGGREGATE_TYPE = "MintAggregate";

    private final RedemptionCutoffRequestRepository requests;
    private final KeySetInventoryPort keySetInventory;
    private final OutboxRepository outboxRepository;
    private final TransactionManager transactionManager;
    private final Clock clock;

    public ManageRedemptionCutoffInteractor(final RedemptionCutoffRequestRepository requests,
                                            final KeySetInventoryPort keySetInventory,
                                            final OutboxRepository outboxRepository,
                                            final TransactionManager transactionManager,
                                            final Clock clock) {
        this.requests = Objects.requireNonNull(requests, "cutoff request repository must not be null");
        this.keySetInventory = Objects.requireNonNull(keySetInventory, "keyset inventory must not be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outbox repository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transaction manager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records a request for a cutoff, pending another Operator's approval.
     *
     * @throws CutoffRequestException when the keyset is unknown, active, or the date gives too little notice
     */
    public CutoffRequest request(final MintId mintId, final String keysetId, final Instant redeemUntil,
                                 final String reason, final String operatorId) {
        Objects.requireNonNull(mintId, "mint id must not be null");
        requireText(keysetId, "keyset id");
        requireText(reason, "reason");
        requireText(operatorId, "operator id");
        if (redeemUntil == null) {
            throw new CutoffRequestException(Kind.INVALID, "redeemUntil is required");
        }
        final Instant now = clock.instant();
        if (redeemUntil.isBefore(now.plus(MINIMUM_NOTICE))) {
            throw new CutoffRequestException(Kind.INVALID,
                "redeemUntil must be at least " + MINIMUM_NOTICE.toHours() + "h ahead, so holders are warned");
        }
        requireArchivedKeyset(mintId, keysetId);

        final CutoffRequest request = new CutoffRequest(UUID.randomUUID(), mintId, keysetId, redeemUntil,
            reason, operatorId, now, Status.PENDING_APPROVAL, null, null, null);
        transactionManager.executeVoid(() -> {
            requests.create(request);
            requests.audit(new AuditEntry(request.requestId(), mintId, keysetId, "CUTOFF_REQUESTED",
                operatorId, now, "redeemUntil=" + redeemUntil + " reason=" + reason));
        });
        return request;
    }

    /**
     * Approves a pending request and queues it for delivery to the mint.
     *
     * @throws CutoffRequestException when the approver is the requester, or the request is not pending
     */
    public CutoffRequest approve(final UUID requestId, final String operatorId) {
        requireText(operatorId, "operator id");
        final CutoffRequest pending = requirePending(requestId);
        if (pending.requestedBy().equals(operatorId)) {
            throw new CutoffRequestException(Kind.FORBIDDEN,
                "the Operator who requested a cutoff cannot approve it");
        }
        final Instant now = clock.instant();
        if (!pending.redeemUntil().isAfter(now)) {
            throw new CutoffRequestException(Kind.CONFLICT,
                "redeemUntil " + pending.redeemUntil() + " has already passed; request a new date");
        }
        // The keyset may have changed since the request (a rotation reinstated it, say).
        requireArchivedKeyset(pending.mintId(), pending.keysetId());

        transactionManager.executeVoid(() -> {
            if (!requests.transition(requestId, Status.PENDING_APPROVAL, Status.APPROVED, operatorId, now, null)) {
                throw new CutoffRequestException(Kind.CONFLICT, "request " + requestId + " was decided meanwhile");
            }
            requests.audit(new AuditEntry(requestId, pending.mintId(), pending.keysetId(), "CUTOFF_APPROVED",
                operatorId, now, null));
            // Committed with the approval, so an approval is never acknowledged without the
            // delivery to the mint being durable.
            outboxRepository.append(new OutboxMessage(UUID.randomUUID(), pending.mintId(), AGGREGATE_TYPE,
                CUTOFF_APPROVED_EVENT, "{\"mintId\":\"" + pending.mintId().asString() + "\"}",
                Map.of(REQUEST_ID_ATTRIBUTE, requestId.toString()), now, now, null, null, 0));
        });
        return requests.findById(requestId).orElseThrow();
    }

    /** Rejects a pending request. Any Operator with the permission may reject, the requester included. */
    public CutoffRequest reject(final UUID requestId, final String operatorId, final String reason) {
        requireText(operatorId, "operator id");
        final CutoffRequest pending = requirePending(requestId);
        final Instant now = clock.instant();
        transactionManager.executeVoid(() -> {
            if (!requests.transition(requestId, Status.PENDING_APPROVAL, Status.REJECTED, operatorId, now, reason)) {
                throw new CutoffRequestException(Kind.CONFLICT, "request " + requestId + " was decided meanwhile");
            }
            requests.audit(new AuditEntry(requestId, pending.mintId(), pending.keysetId(), "CUTOFF_REJECTED",
                operatorId, now, reason));
        });
        return requests.findById(requestId).orElseThrow();
    }

    public List<CutoffRequest> list(final MintId mintId) {
        return requests.findByMintId(mintId);
    }

    private CutoffRequest requirePending(final UUID requestId) {
        Objects.requireNonNull(requestId, "request id must not be null");
        final CutoffRequest request = requests.findById(requestId)
            .orElseThrow(() -> new CutoffRequestException(Kind.NOT_FOUND, "no cutoff request " + requestId));
        if (request.status() != Status.PENDING_APPROVAL) {
            throw new CutoffRequestException(Kind.CONFLICT,
                "request " + requestId + " is " + request.status() + ", not pending approval");
        }
        return request;
    }

    private void requireArchivedKeyset(final MintId mintId, final String keysetId) {
        final List<VaultKeySet> keySets = keySetInventory.listByMint(UUID.fromString(mintId.asString()));
        final VaultKeySet keySet = keySets.stream()
            .filter(candidate -> keysetId.equals(candidate.keySetId()))
            .findFirst()
            .orElseThrow(() -> new CutoffRequestException(Kind.NOT_FOUND,
                "mint " + mintId.asString() + " holds no keyset " + keysetId));
        if (!keySet.archived()) {
            throw new CutoffRequestException(Kind.CONFLICT,
                "keyset " + keysetId + " is active and still signs; a cutoff can only retire an archived keyset");
        }
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new CutoffRequestException(Kind.INVALID, name + " is required");
        }
    }

    /** How a refusal maps to a response. */
    public enum Kind {
        INVALID,
        NOT_FOUND,
        CONFLICT,
        FORBIDDEN
    }

    /** A request the rules above refuse. */
    public static class CutoffRequestException extends RuntimeException {

        private final Kind kind;

        public CutoffRequestException(final Kind kind, final String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
