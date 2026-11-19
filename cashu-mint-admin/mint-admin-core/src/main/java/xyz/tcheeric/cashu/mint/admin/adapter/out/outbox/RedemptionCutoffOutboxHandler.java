package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRedemptionCutoffPort;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.AuditEntry;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.CutoffRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.Status;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageRedemptionCutoffInteractor;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Delivers an approved redemption cutoff to the mint (cashu-mint#551).
 *
 * <p>A mint that refuses the cutoff (the keyset signs again, the date passed) ends the request as
 * {@code FAILED} with the mint's reason, since retrying cannot change the answer. A mint that
 * cannot be reached throws, so the dispatcher retries with backoff.
 */
public class RedemptionCutoffOutboxHandler implements OutboxMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RedemptionCutoffOutboxHandler.class);

    private final RedemptionCutoffRequestRepository requests;
    private final MintRedemptionCutoffPort mint;
    private final Clock clock;

    public RedemptionCutoffOutboxHandler(final RedemptionCutoffRequestRepository requests,
                                         final MintRedemptionCutoffPort mint,
                                         final Clock clock) {
        this.requests = Objects.requireNonNull(requests, "cutoff request repository must not be null");
        this.mint = Objects.requireNonNull(mint, "mint cutoff port must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public void handle(final OutboxMessage message) {
        Objects.requireNonNull(message, "outbox message must not be null");
        if (!ManageRedemptionCutoffInteractor.CUTOFF_APPROVED_EVENT.equals(message.eventType())) {
            return;
        }
        final String requestId = message.attributes().get(ManageRedemptionCutoffInteractor.REQUEST_ID_ATTRIBUTE);
        final Optional<CutoffRequest> found = requestId == null
            ? Optional.empty() : requests.findById(UUID.fromString(requestId));
        if (found.isEmpty() || found.get().status() != Status.APPROVED) {
            // Already applied or failed by an earlier delivery: nothing left to do.
            log.info("Redemption cutoff message {} has no approved request to deliver", message.eventId());
            return;
        }
        deliver(found.get());
    }

    private void deliver(final CutoffRequest request) {
        try {
            mint.apply(request.keysetId(), request.redeemUntil(), request.reason(),
                "mint-admin request " + request.requestId() + " approved by " + request.decidedBy());
            finish(request, Status.APPLIED, "CUTOFF_APPLIED", "mint recorded redeemUntil=" + request.redeemUntil());
            log.warn("Redemption cutoff applied: keyset {} stops redeeming at {}", request.keysetId(),
                request.redeemUntil());
        } catch (final MintRedemptionCutoffPort.Rejected rejected) {
            finish(request, Status.FAILED, "CUTOFF_REFUSED_BY_MINT", rejected.getMessage());
            log.warn("Mint refused redemption cutoff on keyset {}: {}", request.keysetId(), rejected.getMessage());
        }
    }

    private void finish(final CutoffRequest request, final Status next, final String action, final String detail) {
        requests.transition(request.requestId(), Status.APPROVED, next, request.decidedBy(), request.decidedAt(),
            detail);
        requests.audit(new AuditEntry(request.requestId(), request.mintId(), request.keysetId(), action,
            "mint-admin", clock.instant(), detail));
    }
}
