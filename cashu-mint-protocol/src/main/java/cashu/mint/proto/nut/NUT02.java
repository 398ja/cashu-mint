package cashu.mint.proto.nut;

import cashu.common.annotation.Nut;
import cashu.common.model.ActiveKeySet;
import cashu.common.model.KeySet;
import cashu.common.model.Mint;
import cashu.common.model.Proof;
import cashu.common.util.CashuErrorException;
import cashu.util.Configuration;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

import static cashu.mint.proto.nut.NUT01.generateKeySet;

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

    public static int fees(@NonNull List<Proof> inputs) {
        int sum_fees = 0;
        for (Proof proof : inputs) {
            String keysetId = proof.getKeySetId();
            KeySet keySet = keys(keysetId);
            sum_fees += keySet.getPartPerThousand();
        }

        return Math.floorDiv (sum_fees + 999, 1000);
    }

    public static int fees(@NonNull Proof input) {
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
        Configuration configuration = Configuration.load(Objects.requireNonNull(NUT01.class.getResourceAsStream("/app.properties")));
        return configuration.getValues("units");
    }
}
