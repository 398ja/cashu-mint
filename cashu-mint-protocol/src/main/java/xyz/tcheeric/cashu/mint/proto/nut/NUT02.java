package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static xyz.tcheeric.cashu.mint.proto.nut.NUT01.generateKeySet;

@Slf4j
@Nut(2)
public class NUT02 {

    public static List<KeySet> keys(UUID mintId) throws CashuErrorException {
        return keys(mintId, new DefaultMintLoadService());
    }

    public static List<KeySet> keys(UUID mintId, @NonNull MintLoadService mintLoadService) throws CashuErrorException {
        log.debug("keys()");
        Mint mint = mintLoadService.load(mintId, false);
        Set<KeySet> keySets = mint.getKeySets();
        keySets.forEach(keySet -> {
            if (keySet.getId() == null) {
                try {
                    String unit = keySet.getUnit();
                    keySet.setId(generateKeySet(mintId, unit).getId());
                } catch (CashuErrorException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return new ArrayList<>(keySets);
    }

    public static KeySet keys(@NonNull String keysetId, MintLoadService mintLoadService) throws CashuErrorException {
        List<KeySet> keySets = keySets(mintLoadService);
        log.debug("keysets: {}", keySets);
        return keySets
                .stream()
                .filter(keySet -> null != keySet.getId())
                .filter(keySet -> keySet.getId().equals(keysetId))
                .findFirst()
                .orElseThrow();
    }

    public static List<ActiveKeySet> activeKeySets(MintLoadService mintLoadService) throws CashuErrorException {
        log.debug("keySets()");
        List<ActiveKeySet> activeKeySets = new ArrayList<>();
        activeKeySets.addAll(activeKeySets(false, mintLoadService));
        activeKeySets.addAll(activeKeySets(true, mintLoadService));

        // Sort the activeKeySets list by id
        activeKeySets.sort(Comparator.comparing(ActiveKeySet::getId));

        return activeKeySets;
    }

    private static List<KeySet> keySets(MintLoadService mintLoadService) throws CashuErrorException {
        log.debug("keySets()");

        List<KeySet> result = new ArrayList<>();
        result.addAll(keySets(false, mintLoadService));
        result.addAll(keySets(true, mintLoadService));

        return result;
    }

    private static List<KeySet> keySets(boolean archive, MintLoadService mintLoadService) throws CashuErrorException {
        log.debug("keySets({})", archive);

        List<KeySet> result = new ArrayList<>();
        List<Mint> mints = mintLoadService.load(archive);

        if (mints != null) {
            mints.stream().forEach(mint -> {
                result.addAll(mint.getKeySets());
            });
        }

        return result;
    }

    private static List<ActiveKeySet> activeKeySets(boolean archive, MintLoadService mintLoadService) throws CashuErrorException {
        log.debug("activeKeySets({})", archive);

        List<ActiveKeySet> result = new ArrayList<>();
        List<KeySet> keySets = keySets(archive, mintLoadService);
        keySets.stream().map(keySet -> ActiveKeySet.fromKeySet(keySet, !archive)).forEach(result::add);

        return result;
    }
}
