package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;

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
        log.debug("keys()");
        Mint mint = DBMintVault.load(mintId.toString(), false);
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

    public static KeySet keys(@NonNull String keysetId) throws CashuErrorException {
        List<KeySet> keySets = keySets();
        log.debug("keysets: {}", keySets);
        return keySets
                .stream()
                .filter(keySet -> null != keySet.getId())
                .filter(keySet -> keySet.getId().equals(keysetId))
                .findFirst()
                .orElseThrow();
    }

    public static List<ActiveKeySet> activeKeySets() throws CashuErrorException {
        log.debug("keySets()");
        List<ActiveKeySet> activeKeySets = new ArrayList<>();
        activeKeySets.addAll(activeKeySets(false));
        activeKeySets.addAll(activeKeySets(true));

        // Sort the activeKeySets list by id
        activeKeySets.sort(Comparator.comparing(ActiveKeySet::getId));

        return activeKeySets;
    }

    public static <T extends Secret> int fees(@NonNull List<Proof<T>> inputs) throws CashuErrorException {
        int sum_fees = 0;
        for (Proof<T> proof : inputs) {
            String keysetId = proof.getKeySetId();
            KeySet keySet = keys(keysetId);
            sum_fees += keySet.getPartPerThousand();
        }

        return Math.floorDiv (sum_fees + 999, 1000);
    }

    public static <T extends Secret> int fees(@NonNull Proof<T> input) throws CashuErrorException {
        String keysetId = input.getKeySetId();
        KeySet keySet = keys(keysetId);
        return Math.floorDiv(keySet.getPartPerThousand() + 999, 1000);
    }

    private static List<KeySet> keySets(String keySetId) throws CashuErrorException {
        log.debug("keySets()");

        List<KeySet> result = new ArrayList<>();
        result.addAll(keySets(keySetId, false));
        result.addAll(keySets(keySetId, true));

        return result;
    }

    private static List<KeySet> keySets(@NonNull String keySetId, boolean archive) throws CashuErrorException {
        log.debug("keySets({})", archive);

        List<KeySet> result = new ArrayList<>();
        Mint mint = DBMintVault.load(keySetId, archive);

        if (mint != null) {
            result.addAll(mint.getKeySets());
        }

        return result;
    }

    private static List<KeySet> keySets() {
        log.debug("keySets()");

        List<KeySet> result = new ArrayList<>();
        result.addAll(keySets(false));
        result.addAll(keySets(true));

        return result;
    }

    private static List<KeySet> keySets(boolean archive) {
        log.debug("keySets({})", archive);

        List<KeySet> result = new ArrayList<>();
        List<Mint> mints = DBMintVault.load(archive);

        if (mints != null) {
            mints.stream().forEach(mint -> {
                result.addAll(mint.getKeySets());
            });
        }

        return result;
    }

    private static List<ActiveKeySet> activeKeySets(boolean archive) throws CashuErrorException {
        log.debug("activeKeySets({})", archive);

        List<ActiveKeySet> result = new ArrayList<>();
        List<KeySet> keySets = keySets(archive);
        keySets.stream().map(keySet -> ActiveKeySet.fromKeySet(keySet, !archive)).forEach(result::add);

        return result;
    }
}
