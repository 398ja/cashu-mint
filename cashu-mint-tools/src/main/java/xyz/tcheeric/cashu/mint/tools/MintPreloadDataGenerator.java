package xyz.tcheeric.cashu.mint.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Generates deterministic mint preload data and emits it as JSON so that other tooling can
 * translate it into SQL fixtures.
 */
public final class MintPreloadDataGenerator {

    public static final List<Integer> DEFAULT_DENOMINATIONS = List.of(1, 2, 4, 8, 16, 32, 64, 128);
    public static final String DEFAULT_UNIT = "sat";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final UUID mintId;
    private final String unit;
    private final List<Integer> denominations;
    private final Function<Integer, String> privateKeyHexGenerator;

    private MintPreloadData cachedData;

    public MintPreloadDataGenerator(@NonNull UUID mintId, @NonNull String unit, @NonNull List<Integer> denominations) {
        this(mintId, unit, denominations, amount -> null);
    }

    MintPreloadDataGenerator(@NonNull UUID mintId,
                             @NonNull String unit,
                             @NonNull List<Integer> denominations,
                             @NonNull Function<Integer, String> privateKeyHexGenerator) {
        this.mintId = Objects.requireNonNull(mintId, "mintId");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.privateKeyHexGenerator = Objects.requireNonNull(privateKeyHexGenerator, "privateKeyHexGenerator");
        List<Integer> copy = new ArrayList<>(Objects.requireNonNull(denominations, "denominations"));
        copy.sort(Comparator.naturalOrder());
        this.denominations = List.copyOf(copy);
    }

    public MintPreloadData data() {
        if (cachedData == null) {
            cachedData = createData();
        }
        return cachedData;
    }

    public String buildJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise mint preload data", e);
        }
    }

    public void writeJson(@NonNull Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, buildJson(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length > 0 ? Path.of(args[0]) : Path.of("scripts/preload-test-data.json");
        UUID mintId = args.length > 1 ? UUID.fromString(args[1]) : UUID.randomUUID();

        MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, DEFAULT_UNIT, DEFAULT_DENOMINATIONS);
        generator.writeJson(output);

        MintPreloadData data = generator.data();
        System.out.printf("Generated preload JSON for mint %s and keyset %s at %s%n", data.mintId(), data.keySetId(),
                output.toAbsolutePath());
    }

    private MintPreloadData createData() {
        Keys keys = new Keys();
        List<MintPreloadData.DenominationKey> keyMaterials = new ArrayList<>();

        for (Integer amount : denominations) {
            String privateKeyHex = derivePrivateKeyHex(amount);
            PrivateKey privateKey = toPrivateKey(privateKeyHex);
            keys.put(BigInteger.valueOf(amount.longValue()), PrivateKey.derivePublicKey(privateKey));
            UUID keyId = deterministicId(mintId, unit, amount.toString());
            keyMaterials.add(new MintPreloadData.DenominationKey(keyId, amount, privateKeyHex));
        }

        String keySetId = KeySetDerivation.getId(keys.values());
        UUID keySetRowId = deterministicId(mintId, unit, keySetId);
        return new MintPreloadData(mintId, keySetId, keySetRowId, unit, List.copyOf(keyMaterials));
    }

    private String derivePrivateKeyHex(Integer amount) {
        String provided = privateKeyHexGenerator.apply(amount);
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return deriveDeterministicPrivateKey(amount);
    }

    private String deriveDeterministicPrivateKey(Integer amount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = mintId + "|" + unit + "|" + amount;
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static UUID deterministicId(UUID mintId, String unit, String value) {
        String source = mintId + "|" + unit + "|" + value;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static PrivateKey toPrivateKey(String privateKeyHex) {
        try {
            return PrivateKey.fromString(privateKeyHex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid private key hex", e);
        }
    }
}
