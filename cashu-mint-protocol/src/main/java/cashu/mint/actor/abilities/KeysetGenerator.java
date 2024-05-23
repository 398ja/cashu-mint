package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.model.PrivateKey;
import cashu.common.protocol.Ability;
import cashu.crypto.KeySetDerivation;
import cashu.util.Configuration;
import cashu.util.ThreadUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

@Log
@Nut(1)
@AllArgsConstructor
public class KeysetGenerator implements Ability<KeySet> {

    private final String unit;

    @Override
    public KeySet apply() {
        var task = new KeysetGeneratorTask(unit);
        try {
            ThreadUtil.builder().blocking(true).task(task).build().run();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
        return task.getResult();
    }

    static class KeysetGeneratorTask implements ThreadUtil.Task<KeySet> {

        private final String unit;

        @Getter
        private KeySet result;

        public KeysetGeneratorTask(@NonNull String unit) {
            this.unit = unit;
        }

        @Override
        public KeySet execute() {
            Keys keys = getKeys();
            result = KeySet.builder().unit(unit).keys(keys).id(KeySetDerivation.deriveKeySetId(keys)).build();
            return result;
        }

        // TODO - Retrieve the public key from the vault
        private Keys getKeys() {
            log.log(Level.FINEST, "getKeys()");
            Configuration configuration = Configuration.load(Objects.requireNonNull(KeysetGenerator.class.getResourceAsStream("/keyset.properties")));
            Keys keys = new Keys();

            String prefix = "key_" + unit + "_";
            Map<String, String> matchingKeys = configuration.getMatching(prefix);
            matchingKeys.keySet().stream().forEach(key -> {
                BigInteger index = BigInteger.valueOf(Long.parseLong(matchingKeys.get(key).split(":")[0]));
                String privateKey = matchingKeys.get(key).split(":")[1].trim();
                keys.put(index, PrivateKey.derivePublicKey(PrivateKey.fromString(privateKey)));
            });
            return keys;
        }

    }
}
