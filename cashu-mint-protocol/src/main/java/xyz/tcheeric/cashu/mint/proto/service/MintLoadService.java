package xyz.tcheeric.cashu.mint.proto.service;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;
import java.util.UUID;

public interface MintLoadService {
    Mint load(UUID mintId, boolean archive) throws CashuErrorException;

    default Mint load(String mintId, boolean archive) throws CashuErrorException {
        return load(UUID.fromString(mintId), archive);
    }

    List<Mint> load(boolean archive) throws CashuErrorException;

    /**
     * The operating mint {@code mintId}, as swap (NUT-03) and melt (NUT-05) both load it, so the two
     * redemption paths cannot drift apart again (cashu-mint#548).
     *
     * <p>Melt used to ask for {@code load(mintId, true)}. In the vault-backed loader that flag
     * selects a keyset generation AND requires the mint's own archived flag to equal it, so it
     * refused every ACTIVE mint (90008) and only "worked" for a decommissioned one. Loading with
     * {@code false} answers a live mint and refuses one that is itself archived, as swap always has.
     *
     * <p>The proofs' keysets are NOT resolved from this mint: both tasks resolve them through one
     * {@code KeySetDirectory} per request, which reads the active and the archived generation, so a
     * proof from a keyset retired by rotation still redeems (NUT-02). Folding the archived generation
     * into this load as well would read it twice per request, which the keyset-load budget forbids.
     *
     * <p>Static rather than default so a Mockito mock of this interface still runs it.
     *
     * @return the mint, or {@code null} when the delegate answers none
     */
    static Mint loadForRedemption(@NonNull MintLoadService loader, @NonNull UUID mintId)
            throws CashuErrorException {
        return loader.load(mintId, false);
    }

    default List<KeySet> keySets() throws CashuErrorException {
        List<KeySet> result = new java.util.ArrayList<>();
        result.addAll(keySets(false));
        result.addAll(keySets(true));
        return result;
    }

    /**
     * Retrieve a {@link KeySet} by its id.
     *
     * @param keySetId the identifier of the keyset
     * @return the matching keyset or {@code null} if none found
     */
    default KeySet keySet(@NonNull String keySetId) throws CashuErrorException {
        return keySets()
                .stream()
                .filter(keySet -> keySetId.equals(keySet.getId()))
                .findFirst()
                .orElse(null);
    }

    default List<KeySet> keySets(boolean archive) throws CashuErrorException {
        List<KeySet> result = new java.util.ArrayList<>();
        List<Mint> mints = load(archive);
        if (mints != null) {
            for (Mint mint : mints) {
                result.addAll(mint.getKeySets());
            }
        }
        return result;
    }
}
