package cashu.mint.admin;

import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.model.Mint;
import cashu.common.model.PrivateKey;
import cashu.mint.nut.NUT01;
import cashu.util.Configuration;
import cashu.vault.config.KeyConfiguration;
import cashu.vault.config.KeysetConfiguration;
import cashu.vault.config.MintConfiguration;
import cashu.vault.impl.fs.FSKeyVault;
import cashu.vault.impl.fs.FSKeysetVault;
import cashu.vault.impl.fs.FSMintVault;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Bootstrap {

    public static void main(String[] args) {
        try {
            Mint mint = create();
            System.out.println("Mint created: " + mint.getPrivateKey());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Mint create() throws Exception {
        Mint mint = new Mint();
        new FSMintVault(new MintConfiguration(mint.getPrivateKey().toString())).store();

        for (String unit : getUnits()) {
            Map<BigInteger, PrivateKey> keysMap = new HashMap<>();
            Keys keys = new Keys();
            for (Integer key : getKeys(unit)) {
                PrivateKey privateKey = PrivateKey.generateRandom();
                keysMap.put(BigInteger.valueOf(key), privateKey);
                keys.put(BigInteger.valueOf(key), PrivateKey.derivePublicKey(privateKey));
            }

            KeySet keySet = NUT01.generateKeySet(unit, keys);
            mint.addKeySet(keySet);

            KeysetConfiguration keysetConfiguration = new KeysetConfiguration(new MintConfiguration(mint.getPrivateKey().toString()), keySet.getId(), keySet.getUnit());
            new FSKeysetVault(keysetConfiguration).store();

            for (BigInteger key : keysMap.keySet()) {
                new FSKeyVault(new KeyConfiguration(keysetConfiguration, key, keysMap.get(key).toString())).store();
            }
        }

        return mint;
    }

    private static List<String> getUnits() {
        InputStream is = Bootstrap.class.getResourceAsStream("/app.properties");
        return Configuration.load(Objects.requireNonNull(is)).getValues("units");
    }

    private static List<Integer> getKeys(String unit) {
        InputStream is = Bootstrap.class.getResourceAsStream("/keyset.properties");
        return Configuration.load(Objects.requireNonNull(is)).getMatching("key_" + unit + "_").values().stream().map(Integer::parseInt).toList();
    }
}