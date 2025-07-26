package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.UUID;

import java.util.List;

public interface MintLoadService {
    Mint load(UUID mintId, boolean archive) throws CashuErrorException;

    default Mint load(String mintId, boolean archive) throws CashuErrorException {
        return load(UUID.fromString(mintId), archive);
    }

    List<Mint> load(boolean archive) throws CashuErrorException;

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
