package cashu.mint.nut;

import cashu.common.annotation.Nut;
import cashu.common.error.CashuException;
import cashu.common.model.KeySet;
import cashu.util.Configuration;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

import static cashu.mint.nut.NUT01.generateKeySet;

@Nut(2)
public class NUT02 {

    public static List<KeySet> keysets() {
        List<KeySet> keySets = new ArrayList<>();
        var units = getUnits();

        units.stream().forEach(unit -> {
            keySets.add(generateKeySet(unit));
        });

        return keySets;
    }

    public static KeySet keys(@NonNull String keysetId) {
        var keySets = keysets();
        return keySets.stream().filter(keySet -> keySet.getId().equals(keysetId)).findFirst().orElse(null);
    }

    private static List<String> getUnits() {
        Configuration configuration = Configuration.load(NUT01.class.getResourceAsStream("/app.properties"));
        return configuration.getValues("units");
    }
}
