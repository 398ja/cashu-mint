package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.nut02.KeySetResolver;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a NUT-02 keyset id against the keysets this mint has loaded, active and inactive alike.
 *
 * <p>NUT-02 keeps the proofs of an inactive keyset spendable, so a single request routinely spends
 * inputs issued under several keysets. Fee calculation needs each proof priced against its own
 * keyset, which is why {@code PostInputRequest#getFees} takes a resolver rather than one keyset
 * chosen by the caller.
 *
 * <p>The keysets are read once per resolver and cached, so pricing a request with many inputs costs
 * one load rather than one per input. A resolver is therefore scoped to a single request.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/02.md">NUT-02</a>
 */
@Slf4j
public final class MintKeySetResolver implements KeySetResolver {

    private final MintLoadService mintLoadService;
    private Map<String, KeySet> keySetsById;

    public MintKeySetResolver(@NonNull MintLoadService mintLoadService) {
        this.mintLoadService = mintLoadService;
    }

    @Override
    public Optional<KeySet> findById(@NonNull String keySetId) {
        return Optional.ofNullable(loadedKeySets().get(keySetId));
    }

    private Map<String, KeySet> loadedKeySets() {
        if (keySetsById == null) {
            keySetsById = indexById();
        }
        return keySetsById;
    }

    /**
     * A failed load leaves the index empty rather than propagating, so an unresolvable id surfaces
     * as the NUT-02 {@code keyset_not_known} error the caller already handles instead of an
     * unchecked failure from inside fee arithmetic.
     */
    private Map<String, KeySet> indexById() {
        Map<String, KeySet> index = new HashMap<>();
        try {
            for (KeySet keySet : mintLoadService.keySets()) {
                if (keySet.getId() != null) {
                    index.put(keySet.getId(), keySet);
                }
            }
        } catch (CashuErrorException e) {
            log.error("keyset_resolver load_failed cause={}", e.getMessage());
        }
        return index;
    }
}
