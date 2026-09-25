package xyz.tcheeric.cashu.mint.proto.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut02.KeySetResolver;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.List;
import java.util.Optional;

/**
 * The keyset facts {@link ValidateTransactionTask} needs: a keyset's unit, and whether the
 * mint will still sign with it.
 *
 * <p>Tasks reach keysets in different ways, some holding a loaded {@link Mint} and others a
 * {@link MintLoadService}, and the validation rules care about neither. This interface
 * keeps the rules independent of how the keysets were obtained.
 *
 * <p>It is also the {@link KeySetResolver} that NUT-02 fee pricing asks for, so the rules and
 * the fee arithmetic of one request read one snapshot instead of each building its own. Before
 * that, a swap ran {@code ValidateTransactionTask} against a directory and {@code VerifyFeesTask}
 * against a separate resolver, which is two generations read twice: 4 loads for a swap that needs
 * 2. Measured on staging at 22.5 keyset loads and 87 vault key GETs per swap even after each
 * task's own directory was made single-read.
 */
public interface KeySetDirectory extends KeySetResolver {

    /** Reports a load that failed inside {@link #findById}, which cannot propagate it. */
    Logger LOG = LoggerFactory.getLogger(KeySetDirectory.class);

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
     * The NUT-02 view of this directory, for fee pricing.
     *
     * <p>A failed load answers empty rather than propagating, so an unresolvable id surfaces as
     * the NUT-02 {@code keyset_not_known} error the fee caller already handles instead of an
     * unchecked failure from inside fee arithmetic. This is the behaviour of the separate
     * resolver this method replaces.
     *
     * @param keySetId the keyset identifier
     * @return the matching keyset, or empty when it cannot be resolved
     */
    @Override
    default Optional<KeySet> findById(String keySetId) {
        try {
            return Optional.ofNullable(find(keySetId));
        } catch (CashuErrorException loadFailure) {
            LOG.error("keyset_directory load_failed keyset_id={} cause={}",
                    keySetId, loadFailure.getMessage());
            return Optional.empty();
        }
    }

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
     * <p>A directory is constructed by the task that serves one request, shared with the tasks
     * it runs, and dropped when that request returns, so the window in which a keyset rotation
     * is invisible is one request. Within that request, answering the same question two
     * different ways would be a bug rather than a feature: the unit rules, the archived-keyset
     * rule and the fee arithmetic must judge every input and output against one consistent set
     * of keysets.
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
