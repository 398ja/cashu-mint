package xyz.tcheeric.cashu.mint.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.crypto.util.KeySetIdV2Derivation;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Generates mint preload data, with fresh random private keys, and emits it as JSON so that other
 * tooling can translate it into SQL fixtures. The output holds private keys: never commit it.
 */
@Slf4j
public final class MintPreloadDataGenerator {

    /**
     * Powers of two from 1 to 2^23, which is the smallest ladder that reaches the mint's own
     * per-operation amount cap.
     *
     * <p><b>A ladder that stops below the amounts being signed is not a smaller ladder, it is a
     * different cost curve.</b> A greedy largest-first split uses the top denomination
     * repeatedly once the amount outgrows it, so proof count stops tracking the amount's
     * POPCOUNT and starts scaling LINEARLY with its magnitude. Against the old 1..1024 ladder
     * that turned a EUR 25.00 sale (33,246 sat) into 39 proofs rather than 8, and a EUR 1000
     * sale into 1,302 — which is how a token size ceiling came to refuse ordinary trade
     * (398ja/imani-gateway-portal#43).
     *
     * <p>2^23 = 8,388,608 is the largest power of two at or below the 10,000,000 sat cap staging
     * sets (imani-deploy#59). Reaching the cap is the property that matters: every amount the
     * mint will ever sign is then representable in at most 24 proofs, and the curve is flat
     * across the whole range instead of degrading at the top of it.
     *
     * <p>Twenty-four keys rather than eight costs three times the key material per keyset, which
     * is a fixed and small cost — keys are generated once per rotation — against a proof count
     * that was otherwise unbounded in the amount.
     */
    public static final List<Integer> DEFAULT_DENOMINATIONS = List.of(
        1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024,
        2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288,
        1048576, 2097152, 4194304, 8388608);
    public static final String DEFAULT_UNIT = "sat";

    /** Dalia Phase 9: the zero-value IOU keyset — a single {@code 0} denomination, unit {@code "iou"}. */
    public static final String IOU_UNIT = "iou";
    public static final List<Integer> IOU_DENOMINATIONS = List.of(0);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger SECP256K1_N = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

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
        String outputArg = args.length > 0 ? args[0] : null;
        Path output = (outputArg == null || outputArg.isBlank())
                ? Path.of("scripts/preload-test-data.json")
                : Path.of(outputArg);
        String mintIdArg = args.length > 1 ? args[1] : null;
        UUID mintId = (mintIdArg == null || mintIdArg.isBlank())
                ? UUID.randomUUID()
                : UUID.fromString(mintIdArg);

        MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, DEFAULT_UNIT, DEFAULT_DENOMINATIONS);
        generator.writeJson(output);

        MintPreloadData data = generator.data();
        log.info("Generated preload JSON for mint {} and keyset {} at {}", data.mintId(), data.keySetId(),
                output.toAbsolutePath());

        // Dalia Phase 9: also emit the zero-value IOU keyset (single 0 denomination) alongside "sat".
        MintPreloadDataGenerator iouGenerator = new MintPreloadDataGenerator(mintId, IOU_UNIT, IOU_DENOMINATIONS);
        Path iouOutput = output.resolveSibling("iou-" + output.getFileName());
        iouGenerator.writeJson(iouOutput);
        MintPreloadData iouData = iouGenerator.data();
        log.info("Generated IOU preload JSON for mint {} and keyset {} at {}", iouData.mintId(),
                iouData.keySetId(), iouOutput.toAbsolutePath());
    }

    private MintPreloadData createData() {
        Keys keys = new Keys();
        List<MintPreloadData.DenominationKey> keyMaterials = new ArrayList<>(denominations.size());

        for (Integer amount : denominations) {
            String privateKeyHex = derivePrivateKeyHex(amount);
            PrivateKey privateKey = toPrivateKey(privateKeyHex);
            keys.put(BigInteger.valueOf(amount.longValue()), PrivateKey.derivePublicKey(privateKey));
            UUID keyId = deterministicId(mintId, unit, amount.toString());
            keyMaterials.add(new MintPreloadData.DenominationKey(keyId, amount, privateKeyHex));
        }

        String keySetId = KeySetIdV2Derivation.getId(keys.values(), unit, 0, null);
        UUID keySetRowId = deterministicId(mintId, unit, keySetId);
        return new MintPreloadData(mintId, keySetId, keySetRowId, unit, List.copyOf(keyMaterials));
    }

    private String derivePrivateKeyHex(Integer amount) {
        String provided = privateKeyHexGenerator.apply(amount);
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return randomPrivateKey();
    }

    /**
     * A fresh CSPRNG scalar in {@code [1, n-1]} (cashu-mint#545).
     *
     * <p>This used to be {@code SHA-256(mintId|unit|amount)}, so anyone holding the mint id of a
     * preloaded keyset (it is written into the JSON beside the keys) could recompute every private
     * key and forge that mint's ecash. Fixture keys are now random on every generation; nothing
     * public determines them.
     */
    private static String randomPrivateKey() {
        byte[] bytes = new byte[32];
        while (true) {
            RANDOM.nextBytes(bytes);
            BigInteger k = new BigInteger(1, bytes);
            if (k.signum() > 0 && k.compareTo(SECP256K1_N) < 0) {
                return bytesToHex(bytes);
            }
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
