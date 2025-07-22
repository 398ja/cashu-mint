package xyz.tcheeric.cashu.mint.admin;

import lombok.Data;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;
import xyz.tcheeric.cashu.mint.admin.model.KeysDto;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import xyz.tcheeric.cashu.vault.api.config.KeyConfiguration;
import xyz.tcheeric.cashu.vault.api.config.KeysetConfiguration;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeySetVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeyVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

@Data
@Log
@Component
@Deprecated(forRemoval = true)
public class Bootstrap {

    @NonNull
    private final String mintId;

    @NonNull
    private final String unit;


    @Autowired
    public Bootstrap(String mintId, String unit) {
        this.mintId = mintId;
        this.unit = unit;
    }

    public MintDto create() throws Exception {
        MintDto mintDto = new MintDto(mintId);
        new DBMintVault(new MintConfiguration(mintId)).store();

        Map<BigInteger, PrivateKey> keysMap = new HashMap<>();
        KeysDto keysDto = new KeysDto();
        getKeys().forEach(key -> {
            PrivateKey privateKey = PrivateKey.generateRandom();
            keysMap.put(BigInteger.valueOf(key), privateKey);
            keysDto.put(BigInteger.valueOf(key), privateKey);
        });

        KeySetDto keySetDto = generateKeySet(unit, keysDto);
        mintDto.addKeySet(keySetDto);

        KeysetConfiguration keysetConfiguration = new KeysetConfiguration(new MintConfiguration(mintId), keySetDto.getId(), keySetDto.getUnit());
        new DBKeySetVault(keysetConfiguration).store();

        for (BigInteger key : keysMap.keySet()) {
            new DBKeyVault(new KeyConfiguration(keysetConfiguration, key, keysMap.get(key).toString())).store();
        }

        return mintDto;
    }

    public void archive() throws CashuErrorException {
        log.log(Level.INFO, "Archiving mintDto: {0}", mintId);
        DBMintVault vault = new DBMintVault(new MintConfiguration(mintId));
        vault.archive();
    }

    public void delete() throws CashuErrorException {
        log.log(Level.INFO, "Deleting mint");
        new DBMintVault(new MintConfiguration(mintId)).delete();
    }

    @SneakyThrows
    private Set<Integer> getKeys() {
        return Set.of(1, 2, 4, 8, 16);
    }

    private static KeySetDto generateKeySet(@NonNull String unit, @NonNull KeysDto keysDto) {
        KeySetDto keySetDto = KeySetDto.builder().unit(unit).keys(keysDto).build();
        keySetDto.setId(KeySetDerivation.getId(keysDto.values()));
        return keySetDto;
    }

}