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
        List<KeySet> keySets = mintLoadService.keySets();
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
        List<ActiveKeySet> result = new ArrayList<>();

        mintLoadService.keySets(false)
                .stream()
                .map(keySet -> ActiveKeySet.fromKeySet(keySet, true))
                .forEach(result::add);

        mintLoadService.keySets(true)
                .stream()
                .map(keySet -> ActiveKeySet.fromKeySet(keySet, false))
                .forEach(result::add);

        // Sort the activeKeySets list by id
        result.sort(Comparator.comparing(ActiveKeySet::getId));

        return result;
    }
}
