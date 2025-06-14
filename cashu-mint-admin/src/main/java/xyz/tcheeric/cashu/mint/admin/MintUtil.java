package xyz.tcheeric.cashu.mint.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.PrivateKey;
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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

@Setter
@Getter
@Log
public class MintUtil {

    @Setter(AccessLevel.NONE)
    private MintDto mint;

    private final String mintId;
    private final String unit;
    private final URL keysetProperties;


    public MintUtil(@NonNull String mintId, @NonNull String unit) throws Exception {
        this(mintId, unit, Objects.requireNonNull(MintUtil.class.getResource("/keyset.properties")));
    }

    public MintUtil(@NonNull String mintId, @NonNull String unit, @NonNull URL keysetProperties) throws Exception {
        this.mintId = mintId;
        this.unit = unit;
        this.keysetProperties = keysetProperties;
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

    private MintDto createMint() throws Exception {
        MintDto mintDto = new MintDto(mintId);
        new FSMintVault(new MintConfiguration(mintId)).store();

        Map<BigInteger, PrivateKey> keysMap = new HashMap<>();
        KeysDto keysDto = new KeysDto();
        for (Integer key : getKeys()) {
            PrivateKey privateKey = getPrivateKey(key);
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

    @SneakyThrows
    private List<Integer> getKeys() {
        Configuration configuration = new Configuration("key", keysetProperties);
        return configuration.keys().stream()
                .map(Integer::parseInt)
                .toList();
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