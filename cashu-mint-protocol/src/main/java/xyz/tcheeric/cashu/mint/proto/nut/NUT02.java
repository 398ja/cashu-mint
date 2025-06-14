package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;
import xyz.tcheeric.common.util.Configuration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

import static xyz.tcheeric.cashu.mint.proto.nut.NUT01.generateKeySet;

@Log
@Nut(2)
public class NUT02 {

    public static List<KeySet> keys() {
        log.log(Level.FINE, "keysets()");
        List<KeySet> keySets = new ArrayList<>();
        var units = getUnits();
        log.log(Level.FINE, "units: {0}", units);

        units.forEach(unit -> {
            try {
                keySets.add(generateKeySet(unit));
            } catch (CashuErrorException e) {
                throw new RuntimeException(e);
            }
        });

        return keySets;
    }

    public static KeySet keys(@NonNull String keysetId) {
        List<KeySet> keySets = keySets();
        log.log(Level.FINE, "keysets: {0}", keySets);
        return keySets
                .stream()
                .filter(keySet -> null != keySet.getId())
                .filter(keySet -> keySet.getId().equals(keysetId))
                .findFirst()
                .orElseThrow();
    }

    public static List<ActiveKeySet> activeKeySets() {
        log.log(Level.FINE, "keySets()");
        List<ActiveKeySet> activeKeySets = new ArrayList<>();
        activeKeySets.addAll(activeKeySets(false));
        activeKeySets.addAll(activeKeySets(true));

        // Sort the activeKeySets list by id
        activeKeySets.sort(Comparator.comparing(ActiveKeySet::getId));

        return activeKeySets;
    }

    public static <T extends Secret> int fees(@NonNull List<Proof<T>> inputs) {
        int sum_fees = 0;
        for (Proof<T> proof : inputs) {
            String keysetId = proof.getKeySetId();
            KeySet keySet = keys(keysetId);
            sum_fees += keySet.getPartPerThousand();
        }

        return Math.floorDiv (sum_fees + 999, 1000);
    }

    public static <T extends Secret> int fees(@NonNull Proof<T> input) {
        String keysetId = input.getKeySetId();
        KeySet keySet = keys(keysetId);
        return Math.floorDiv(keySet.getPartPerThousand() + 999, 1000);
    }

    private static List<KeySet> keySets() {
        log.log(Level.FINE, "keySets()");

        List<KeySet> result = new ArrayList<>();
        result.addAll(keySets(false));
        result.addAll(keySets(true));

        return result;
    }

    private static List<KeySet> keySets(boolean archive) {
        log.log(Level.FINE, "keySets({0})", archive);

        List<KeySet> result = new ArrayList<>();
        Mint mint = FSMintVault.load(archive);

        if (mint != null) {
            result.addAll(mint.getKeySets());
        }

        return result;
    }

    private static List<ActiveKeySet> activeKeySets(boolean archive) {
        log.log(Level.FINE, "keySets({0})", archive);

        List<ActiveKeySet> result = new ArrayList<>();
        List<KeySet> keySets = keySets(archive);
        keySets.stream().map(keySet -> ActiveKeySet.fromKeySet(keySet, !archive)).forEach(result::add);

        return result;
    }

    private static List<String> getUnits() {
        Configuration configuration = new Configuration("cashu");
        return List.of(configuration.get("units").split(","));
    }
}
