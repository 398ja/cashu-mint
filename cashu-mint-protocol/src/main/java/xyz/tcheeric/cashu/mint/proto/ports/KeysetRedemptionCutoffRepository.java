package xyz.tcheeric.cashu.mint.proto.ports;

import java.util.List;
import java.util.Optional;

/**
 * Durable per-keyset redemption cutoffs (cashu-mint#551).
 *
 * <p>Durable for the same reason suspension is (ADR-0007): a mint that restarts and redeems a
 * retired keyset again is the failure the cutoff exists to prevent. Read on every swap and melt,
 * and deliberately not behind the keyset cache, so a cutoff takes effect at its instant rather
 * than up to a cache TTL later.
 */
public interface KeysetRedemptionCutoffRepository {

    /**
     * @param keysetId keyset to look up
     * @return the cutoff on that keyset, or empty when it redeems indefinitely
     */
    Optional<KeysetRedemptionCutoff> find(String keysetId);

    /** @return every recorded cutoff, passed or not */
    List<KeysetRedemptionCutoff> findAll();

    /**
     * Records or replaces the cutoff on a keyset, and appends the change to its history.
     *
     * <p>Whether the keyset may carry a cutoff at all (it must be archived, and a cutoff that has
     * passed is final) is the caller's check: this port records, it does not judge.
     *
     * @param cutoff the cutoff to record
     */
    void save(KeysetRedemptionCutoff cutoff);
}
