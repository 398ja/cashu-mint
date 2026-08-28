package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

/**
 * The keyset facts {@link ValidateTransactionTask} needs: a keyset's unit, and whether the
 * mint will still sign with it.
 *
 * <p>Tasks reach keysets in different ways — some hold a loaded {@link Mint}, others a
 * {@link MintLoadService} — and the validation rules care about neither. This interface
 * keeps the rules independent of how the keysets were obtained.
 */
public interface KeySetDirectory {

    /**
     * @param keySetId the keyset identifier
     * @return the matching keyset, or {@code null} when the mint does not know it
     */
    KeySet find(String keySetId) throws CashuErrorException;

    /**
     * @param keySetId the keyset identifier
     * @return {@code true} when the mint knows this keyset to be retired from signing
     */
    boolean isArchived(String keySetId) throws CashuErrorException;

    /** Reads keysets from a load service, which distinguishes active from archived ones. */
    static KeySetDirectory of(MintLoadService mintLoadService) {
        return new KeySetDirectory() {
            @Override
            public KeySet find(String keySetId) throws CashuErrorException {
                return mintLoadService.keySet(keySetId);
            }

            @Override
            public boolean isArchived(String keySetId) throws CashuErrorException {
                return contains(keySetId, true) && !contains(keySetId, false);
            }

            private boolean contains(String keySetId, boolean archived) throws CashuErrorException {
                var keySets = mintLoadService.keySets(archived);
                return keySets != null && keySets.stream()
                        .anyMatch(keySet -> keySet != null && keySetId.equals(keySet.getId()));
            }
        };
    }

    /**
     * Reads keysets from an already-loaded mint.
     *
     * <p>A mint loaded for signing carries only the keysets it will sign with, so nothing
     * reachable through it is archived; the signing step still refuses a keyset it cannot
     * resolve.
     */
    static KeySetDirectory of(Mint mint) {
        return new KeySetDirectory() {
            @Override
            public KeySet find(String keySetId) {
                if (mint.getKeySets() == null) {
                    return null;
                }
                return mint.getKeySets().stream()
                        .filter(keySet -> keySet != null && keySetId.equals(keySet.getId()))
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public boolean isArchived(String keySetId) {
                return false;
            }
        };
    }
}
