package xyz.tcheeric.cashu.mint.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.PrivateKey;
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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

@Setter
@Getter
@Log
@Component
public class MintUtil {

    @Setter(AccessLevel.NONE)
    private MintDto mint;

    private final String mintId;
    private final String unit;


    @Autowired
    public MintUtil(@NonNull String mintId, @NonNull String unit) {
        this.mintId = mintId;
        this.unit = unit;
        this.mint = createMint();
    }

    public void write() throws Exception {
        String homeDirectory = System.getProperty("user.home");
        try (FileOutputStream outputStream = new FileOutputStream(homeDirectory + "/mint.json")) {
            write(outputStream);
        }
    }

    public void write(@NonNull OutputStream outputStream) throws IOException {
        log.log(Level.INFO, "Writing mint");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(outputStream, mint);
    }

    public void read(@NonNull InputStream inputStream) throws IOException {
        log.log(Level.INFO, "Reading mint");
        ObjectMapper mapper = new ObjectMapper();
        this.mint = mapper.readValue(inputStream, MintDto.class);
    }

    protected PrivateKey getPrivateKey(@NonNull Integer key) {
        return PrivateKey.generateRandom();
    }

    // TODO: Segregate the creation of the MintDto and the storage of the vaults
    private MintDto createMint() {

        new DBMintVault(new MintConfiguration(mintId)).store();

        Map<BigInteger, PrivateKey> keysMap = new HashMap<>();
        KeysDto keysDto = new KeysDto();
        getKeys().forEach(key -> {
            PrivateKey privateKey = getPrivateKey(key);
            keysMap.put(BigInteger.valueOf(key), privateKey);
            keysDto.put(BigInteger.valueOf(key), privateKey);
        });

        KeySetDto keySetDto = generateKeySet(unit, keysDto);

        KeysetConfiguration keysetConfiguration = new KeysetConfiguration(new MintConfiguration(mintId), keySetDto.getId(), keySetDto.getUnit());
        new DBKeySetVault(keysetConfiguration).store();

        for (BigInteger key : keysMap.keySet()) {
            new DBKeyVault(new KeyConfiguration(keysetConfiguration, key, keysMap.get(key).toString())).store();
        }

        MintDto mintDto = new MintDto(mintId);
        mintDto.addKeySet(keySetDto);

        return mintDto;
    }

    private Set<Integer> getKeys() {
        return Set.of(1, 2, 4, 8, 16);
/*
        Configuration configuration = new Configuration("key", keysetProperties);
        return configuration.keys().stream()
                .map(Integer::parseInt)
                .toList();
*/
    }

    private static KeySetDto generateKeySet(@NonNull String unit, @NonNull KeysDto keysDto) {
        KeySetDto keySetDto = KeySetDto.builder().unit(unit).keys(keysDto).build();
        keySetDto.setId(KeySetDerivation.getId(KeysDto.toKeys(keysDto).values()));
        return keySetDto;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java MintUtil <mintId> <unit>");
            System.exit(1);
        }

        String mintId = args[0];
        String unit = args[1];

        try {
            MintUtil mintUtil = new MintUtil(mintId, unit);
            mintUtil.write();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}