package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.Hex;
import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.model.PublicKey;
import cashu.common.protocol.Ability;
import cashu.crypto.KeySetDerivation;
import cashu.util.Configuration;
import cashu.util.Utils;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.math.BigInteger;
import java.util.Map;

@Nut(1)
@AllArgsConstructor
public class KeysetGenerator implements Ability<KeySet> {

    private final String unit;

    @Override
    public KeySet apply() {
        Keys keys = getKeys(unit);
        return KeySet.builder().unit(unit).keys(keys).id(KeySetDerivation.deriveKeySetId(keys)).build();
    }

    private static Keys getKeys(@NonNull String currency) {
        Configuration configuration = Configuration.load(KeysetGenerator.class.getResourceAsStream("/keyset.properties"));
        Keys keys = new Keys();

        String prefix = "key_" + currency + "_";
        Map<String, String> matchingKeys = configuration.getMatching(prefix);
        matchingKeys.keySet().stream().forEach(key -> {
            BigInteger index = BigInteger.valueOf(Long.parseLong(matchingKeys.get(key).split(":")[0]));
            String privateKey = matchingKeys.get(key).split(":")[1].trim();
            keys.put(index, PublicKey.fromBytes(Utils.getPublicKey(Hex.fromString(privateKey).toBytes())));
        });
        return keys;
    }

}
