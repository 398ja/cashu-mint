package xyz.tcheeric.cashu.mint.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;
import xyz.tcheeric.cashu.mint.admin.model.KeysDto;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.vault.config.KeyConfiguration;
import xyz.tcheeric.cashu.vault.config.KeysetConfiguration;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSKeyVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSKeysetVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;
import xyz.tcheeric.common.util.Configuration;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

@AllArgsConstructor
@Data
@Log
@Deprecated(forRemoval = true)
public class Bootstrap {

    @NonNull
    private final String mintId;

    @NonNull
    private final String unit;

    @NonNull
    private final URL keysetProperties;

    public Bootstrap(String mintId, String unit) {
        this(
                mintId,
                unit,
                Objects.requireNonNull(Bootstrap.class.getResource("/keyset.properties"))
        );
    }

    public MintDto create() throws Exception {
        MintDto mintDto = new MintDto(mintId);
        new FSMintVault(new MintConfiguration(mintId)).store();

        Map<BigInteger, PrivateKey> keysMap = new HashMap<>();
        KeysDto keysDto = new KeysDto();
        for (Integer key : getKeys()) {
            PrivateKey privateKey = PrivateKey.generateRandom();
            keysMap.put(BigInteger.valueOf(key), privateKey);
            keysDto.put(BigInteger.valueOf(key), privateKey);
        }

        KeySetDto keySetDto = generateKeySet(unit, keysDto);
        mintDto.addKeySet(keySetDto);

        KeysetConfiguration keysetConfiguration = new KeysetConfiguration(new MintConfiguration(mintId), keySetDto.getId(), keySetDto.getUnit());
        new FSKeysetVault(keysetConfiguration).store();

        for (BigInteger key : keysMap.keySet()) {
            new FSKeyVault(new KeyConfiguration(keysetConfiguration, key, keysMap.get(key).toString())).store();
        }

        return mintDto;
    }

    public void archive() throws CashuErrorException {
        log.log(Level.INFO, "Archiving mintDto: {0}", mintId);
        FSMintVault vault = new FSMintVault(new MintConfiguration(mintId));
        vault.archive(mintId);
    }

    public void delete() throws CashuErrorException {
        log.log(Level.INFO, "Deleting mint");
        new FSMintVault(mintId).delete();
    }

    @SneakyThrows
    private List<Integer> getKeys() {
        Configuration configuration = new Configuration("keyset", keysetProperties);
        return configuration.keys().stream()
                //.map(key -> key.replace("key.", ""))
                .map(Integer::parseInt)
                .toList();
        //return Configuration.load(keysetProperties, "key").getAll().stream().map(Integer::parseInt).toList();
    }

    private static KeySetDto generateKeySet(@NonNull String unit, @NonNull KeysDto keysDto) {
        KeySetDto keySetDto = KeySetDto.builder().unit(unit).keys(keysDto).build();
        keySetDto.setId(KeySetDerivation.getId(keysDto.values()));
        return keySetDto;
    }

}