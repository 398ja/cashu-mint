package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.List;

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

    /**
     * Reads keysets from a load service, which distinguishes active from archived ones.
     *
     * <p>The two generations are read once per directory and then answered from memory.
     * Without that, every question cost a full vault load, and the validation rules ask one
     * question per input and two per output. A swap of n inputs and n outputs therefore cost
     * 5n loads: measured at 5, 10 and 15 loads for n = 1, 2 and 3. Each load is itself
     * O(keys) HTTP calls to the vault (one per key, to resolve private material; see
     * {@code CachingMintLoadService}), which is how a single swap reached ~98 vault key GETs
     * with the same keyset refetched 5-8 times.
     *
     * <p>A directory is constructed per task and discarded with it, so the window in which a
     * keyset rotation is invisible is one request. Within that request, answering the same
     * question two different ways would be a bug rather than a feature: the balance and unit
     * rules must judge every input and output against one consistent set of keysets.
     *
     * <p>The load is deferred to the first question, so a rule that is never evaluated costs
     * nothing, and a failure still surfaces as the {@link CashuErrorException} the caller
     * already handles rather than at construction.
     */
    static KeySetDirectory of(MintLoadService mintLoadService) {
        return new KeySetDirectory() {

            private List<KeySet> activeKeySets;
            private List<KeySet> archivedKeySets;

            @Override
            public KeySet find(String keySetId) throws CashuErrorException {
                // Active first, matching MintLoadService.keySet, which concatenates the active
                // generation ahead of the archived one and takes the first match.
                KeySet active = findIn(active(), keySetId);
                return active != null ? active : findIn(archived(), keySetId);
            }

            @Override
            public boolean isArchived(String keySetId) throws CashuErrorException {
                return findIn(archived(), keySetId) != null && findIn(active(), keySetId) == null;
            }

            private KeySet findIn(List<KeySet> keySets, String keySetId) {
                return keySets.stream()
                        .filter(keySet -> keySet != null && keySetId.equals(keySet.getId()))
                        .findFirst()
                        .orElse(null);
            }

            private List<KeySet> active() throws CashuErrorException {
                if (activeKeySets == null) {
                    activeKeySets = load(false);
                }
                return activeKeySets;
            }

            private List<KeySet> archived() throws CashuErrorException {
                if (archivedKeySets == null) {
                    archivedKeySets = load(true);
                }
                return archivedKeySets;
            }

            /** An absent generation is an empty one, so a null never reaches the rules above. */
            private List<KeySet> load(boolean archive) throws CashuErrorException {
                List<KeySet> keySets = mintLoadService.keySets(archive);
                return keySets == null ? List.of() : keySets;
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
