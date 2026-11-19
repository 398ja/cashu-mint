package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoffRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Refuses inputs on a keyset whose redemption cutoff has passed (cashu-mint#551).
 *
 * <p>An archived keyset keeps redeeming indefinitely (ADR-0004) unless an operator set a cutoff on
 * it. From the cutoff on, swap and melt refuse its proofs with {@code 12003 keyset_expired}, which
 * is distinct from {@code 12002 keyset_inactive}: an inactive keyset is told "sign elsewhere", an
 * expired one "this value is no longer redeemable here", and a wallet that retried the first
 * advice on the second would loop.
 *
 * <p>Checked before any proof is verified, held or burned, so a refused request leaves nothing
 * behind. The repository is read directly rather than through the keyset cache, so the refusal
 * starts at the cutoff instant and not up to a cache TTL later.
 *
 * <p>With no repository installed (the JPA module disabled) no keyset carries a cutoff, matching
 * how the other durable records degrade.
 */
@Slf4j
public final class RedemptionCutoffGate {

    /** Operation label for a refused swap input. */
    public static final String SWAP = "swap";

    /** Operation label for a refused melt input. */
    public static final String MELT = "melt";

    private final KeysetRedemptionCutoffRepository cutoffs;
    private final Clock clock;

    /**
     * @param cutoffs the cutoff record, or {@code null} when none is installed
     * @param clock   the clock the cutoff instant is judged against
     */
    public RedemptionCutoffGate(KeysetRedemptionCutoffRepository cutoffs, Clock clock) {
        this.cutoffs = cutoffs;
        this.clock = clock;
    }

    /** @return the gate over the installed cutoff record and the system clock */
    public static RedemptionCutoffGate installed() {
        return new RedemptionCutoffGate(MintIntegrityContext.keysetRedemptionCutoffRepository(),
                Clock.systemUTC());
    }

    /**
     * @param inputs    the proofs the request spends
     * @param keySets   the request's keyset snapshot; only a keyset it reports archived is judged
     * @param operation {@link #SWAP} or {@link #MELT}, for the refusal metric
     * @throws CashuErrorException {@code keyset_expired} when any input names a keyset past its cutoff
     */
    public <T extends Secret> void requireRedeemable(List<Proof<T>> inputs, KeySetDirectory keySets,
                                                     String operation)
            throws CashuErrorException {
        if (cutoffs == null || inputs == null || inputs.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        // One lookup per distinct keyset, not per input: a request names one or two keysets.
        Map<String, Optional<KeysetRedemptionCutoff>> byKeyset = new HashMap<>();
        for (Proof<T> input : inputs) {
            if (input == null || input.getKeySetId() == null) {
                continue;
            }
            String keysetId = input.getKeySetId();
            Optional<KeysetRedemptionCutoff> cutoff = byKeyset.computeIfAbsent(keysetId, cutoffs::find);
            if (cutoff.isPresent() && cutoff.get().hasPassed(now) && isArchived(keySets, keysetId)) {
                refuse(keysetId, cutoff.get(), operation);
            }
        }
    }

    /**
     * The signing keyset is never retired, whatever the record says. Admin and mint both refuse a
     * cutoff on a keyset that still signs, so a record on one is a fault, and refusing the only
     * keyset that can sign would take the mint's redemption down with it. Logged so it is seen.
     */
    private static boolean isArchived(KeySetDirectory keySets, String keysetId) throws CashuErrorException {
        if (keySets == null || keySets.isArchived(keysetId)) {
            return true;
        }
        log.error("redemption_cutoff ignored keyset_id={} reason=keyset_is_active", keysetId);
        return false;
    }

    /**
     * @return the cutoff per keyset id, passed or not, read once, for building a listing
     */
    public Map<String, KeysetRedemptionCutoff> all() {
        if (cutoffs == null) {
            return Map.of();
        }
        Map<String, KeysetRedemptionCutoff> byKeyset = new HashMap<>();
        for (KeysetRedemptionCutoff cutoff : cutoffs.findAll()) {
            byKeyset.put(cutoff.keysetId(), cutoff);
        }
        return byKeyset;
    }

    /** @return the instant cutoffs are judged against */
    public Instant now() {
        return clock.instant();
    }

    private void refuse(String keysetId, KeysetRedemptionCutoff cutoff, String operation)
            throws CashuErrorException {
        log.warn("redemption_cutoff refused keyset_id={} operation={} redeem_until={}",
                keysetId, operation, cutoff.redeemUntil());
        MetricRecorders.keysetRedemption().refused(keysetId, operation);
        throw new CashuErrorException(CashuErrorCode.keyset_expired,
                "Keyset " + keysetId + " stopped redeeming at " + cutoff.redeemUntil()
                        + " and its proofs are no longer accepted.");
    }
}
