package xyz.tcheeric.cashu.mint.admin;

import cashu.util.Configuration;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.model.PrivateKey;
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

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Slf4j
public class Bootstrap {

    @NonNull
    private final InputStream appProperties;

    @NonNull
    private final InputStream keysetProperties;

    public Bootstrap() {
        this(
                Objects.requireNonNull(Bootstrap.class.getResourceAsStream("/cashu.properties")),
                Objects.requireNonNull(Bootstrap.class.getResourceAsStream("/keyset.properties"))
        );
    }

    public static void main(String[] args) {
        try {
            Bootstrap bootstrap = new Bootstrap();
            MintDto mintDto = bootstrap.create();
            System.out.println("MintDto created: " + mintDto.getId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public MintDto create() throws Exception {
        MintDto mintDto = new MintDto(UUID.randomUUID().toString());
        String id = mintDto.getId();
        new FSMintVault(new MintConfiguration(id)).store();

        for (String unit : getUnits()) {
            Map<BigInteger, PrivateKey> keysMap = new HashMap<>();
            KeysDto keysDto = new KeysDto();
            for (Integer key : getKeys(unit)) {
                PrivateKey privateKey = PrivateKey.generateRandom();
                keysMap.put(BigInteger.valueOf(key), privateKey);
                keysDto.put(BigInteger.valueOf(key), privateKey);
            }

            KeySetDto keySetDto = generateKeySet(unit, keysDto);
            mintDto.addKeySet(keySetDto);

            KeysetConfiguration keysetConfiguration = new KeysetConfiguration(new MintConfiguration(id), keySetDto.getId(), keySetDto.getUnit());
            new FSKeysetVault(keysetConfiguration).store();

            for (BigInteger key : keysMap.keySet()) {
                new FSKeyVault(new KeyConfiguration(keysetConfiguration, key, keysMap.get(key).toString())).store();
            }
        }

        return mintDto;
    }

    public void archive(@NonNull MintDto mintDto) throws CashuErrorException {
        String id = mintDto.getId();
        log.info("Archiving mintDto: {}", id);
        FSMintVault vault = new FSMintVault(new MintConfiguration(id));
        vault.archive(id);
    }

    public void delete(@NonNull MintDto mintDto) throws CashuErrorException {
        log.info("Deleting mint");
        new FSMintVault(mintDto.getId()).delete();
    }

    private List<String> getUnits() {
        return Configuration.load(appProperties).getValues("units");
    }

    private List<Integer> getKeys(String unit) {
        return Configuration.load(keysetProperties).getMatching("key_" + unit + "_").values().stream().map(Integer::parseInt).toList();
    }

    private static KeySetDto generateKeySet(@NonNull String unit, @NonNull KeysDto keysDto) {
        KeySetDto keySetDto = KeySetDto.builder().unit(unit).keys(keysDto).build();
/*
        KeySet keySet = KeySetDto.toKeySet(keySetDto);
        KeySetDerivation keySetDerivation = new KeySetDerivation(keySet);
        //keySetDerivation.deriveKeySetId();
*/
        keySetDto.setId(KeySetDerivation.getId(keysDto.values()));
        return keySetDto;
    }

}