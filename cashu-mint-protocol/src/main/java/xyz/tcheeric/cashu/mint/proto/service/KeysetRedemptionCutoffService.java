package xyz.tcheeric.cashu.mint.proto.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoffRepository;
import xyz.tcheeric.cashu.mint.proto.tasks.KeySetDirectory;

/**
 * Sets a keyset's redemption cutoff, after checking it may carry one (cashu-mint#551).
 *
 * <p>The rules live here, on the mint, rather than only in the admin, because the mint is the
 * authority on its own keysets (ADR-0007): the admin's view of which keyset signs is a belief,
 * the mint's loaded keysets are the fact.
 *
 * <ul>
 *   <li>Only an archived keyset may carry a cutoff. The signing keyset never does: refusing its
 *       proofs would refuse every proof the mint is issuing now.</li>
 *   <li>A cutoff must lie in the future when it is set. Retiring redemption is a dated,
 *       announced deadline, never an instant switch.</li>
 *   <li>A cutoff that has passed is final. Moving it would make a keyset redeem again after
 *       holders and auditors were told it had stopped, and it is the forged proofs that would
 *       come back first.</li>
 * </ul>
 */
public class KeysetRedemptionCutoffService {

    private final KeysetRedemptionCutoffRepository cutoffs;
    private final MintLoadService mintLoadService;
    private final Clock clock;

    public KeysetRedemptionCutoffService(KeysetRedemptionCutoffRepository cutoffs,
                                         MintLoadService mintLoadService,
                                         Clock clock) {
        this.cutoffs = Objects.requireNonNull(cutoffs, "cutoff repository must not be null");
        this.mintLoadService = Objects.requireNonNull(mintLoadService, "mint load service must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** @return every recorded cutoff */
    public List<KeysetRedemptionCutoff> list() {
        return cutoffs.findAll();
    }

    /**
     * @param keysetId    the keyset to retire for redemption
     * @param redeemUntil the first instant its proofs are refused
     * @param reason      why, for the record
     * @param setBy       who asked, for the record
     * @return the recorded cutoff
     * @throws CutoffRefusedException when the keyset may not carry this cutoff
     * @throws CashuErrorException    when the keysets cannot be loaded
     */
    public KeysetRedemptionCutoff set(String keysetId, Instant redeemUntil, String reason, String setBy)
            throws CashuErrorException {
        if (keysetId == null || keysetId.isBlank()) {
            throw new CutoffRefusedException(Refusal.UNKNOWN_KEYSET, "keyset id is required");
        }
        if (redeemUntil == null) {
            throw new CutoffRefusedException(Refusal.NOT_IN_FUTURE, "redeemUntil is required");
        }
        Instant now = clock.instant();
        requireArchived(keysetId);
        Optional<KeysetRedemptionCutoff> existing = cutoffs.find(keysetId);
        if (existing.isPresent() && existing.get().hasPassed(now)) {
            throw new CutoffRefusedException(Refusal.ALREADY_RETIRED,
                    "keyset " + keysetId + " stopped redeeming at " + existing.get().redeemUntil()
                            + "; a passed cutoff is final");
        }
        if (!redeemUntil.isAfter(now)) {
            throw new CutoffRefusedException(Refusal.NOT_IN_FUTURE,
                    "redeemUntil must be in the future; got " + redeemUntil);
        }
        KeysetRedemptionCutoff cutoff = new KeysetRedemptionCutoff(keysetId, redeemUntil, reason, setBy, now);
        cutoffs.save(cutoff);
        return cutoff;
    }

    private void requireArchived(String keysetId) throws CashuErrorException {
        KeySetDirectory keySets = KeySetDirectory.of(mintLoadService);
        if (keySets.find(keysetId) == null) {
            throw new CutoffRefusedException(Refusal.UNKNOWN_KEYSET, "the mint holds no keyset " + keysetId);
        }
        if (!keySets.isArchived(keysetId)) {
            throw new CutoffRefusedException(Refusal.ACTIVE_KEYSET,
                    "keyset " + keysetId + " is active and still signs; archive it by rotating first");
        }
    }

    /** Why a cutoff was refused, so the caller can map each to its own status. */
    public enum Refusal {
        UNKNOWN_KEYSET,
        ACTIVE_KEYSET,
        NOT_IN_FUTURE,
        ALREADY_RETIRED
    }

    /** A cutoff the rules above do not allow. */
    public static class CutoffRefusedException extends RuntimeException {

        private final Refusal refusal;

        public CutoffRefusedException(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }
}
