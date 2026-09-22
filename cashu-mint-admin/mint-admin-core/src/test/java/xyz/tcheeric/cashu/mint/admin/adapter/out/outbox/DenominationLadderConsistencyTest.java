package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every copy of the denomination ladder must agree.
 *
 * <p>The ladder exists in four places, in three different reactors and two different
 * languages of declaration:
 *
 * <ul>
 *   <li>{@code VaultProvisioningOutboxHandler} — provisions a NEW mint</li>
 *   <li>{@code MintPreloadDataGenerator} — seeds preload fixtures</li>
 *   <li>{@code keyset.properties} — the protocol module's key list</li>
 *   <li>{@code tools/provision-mint/ProvisionMint.java} — the operator script</li>
 * </ul>
 *
 * <p>Found by review rather than by a failure. {@code DenominationLadderTest} asserts real
 * properties — powers of two, no gaps, reaches the cap, popcount-bounded proof counts — but
 * only against {@code MintPreloadDataGenerator}. Truncating the outbox handler's copy to
 * {@code 2^20} and running the entire suite produced no failure at all, while a mint
 * provisioned by that path would silently be unable to sign anything above ~1M: exactly the
 * defect the ladder was widened to fix, reintroduced through the door nobody was watching.
 *
 * <p>Comparing sources rather than constants is deliberate. The three Java copies are
 * {@code private static final} in modules that do not depend on one another, and the fourth
 * is a properties file. Extracting a shared constant would mean a new shared module for one
 * list, and would still leave the properties file and the standalone script outside it. What
 * matters is not where the list lives but that every copy says the same thing, and that is
 * checkable directly.
 *
 * <p>The correct fix if this ever becomes painful is to generate the other three from one
 * source. Until then, this test is what makes the duplication safe rather than merely
 * present.
 */
@DisplayName("the denomination ladder is identical everywhere it is written down")
class DenominationLadderConsistencyTest {

    /** Walks up from the module directory to the repository root. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("cashu-mint-protocol"))) {
            here = here.getParent();
        }
        if (here == null) {
            throw new IllegalStateException(
                    "could not locate the repository root from " + Path.of("").toAbsolutePath());
        }
        return here;
    }

    private static final Pattern JAVA_LADDER = Pattern.compile(
            "DEFAULT_DENOMINATIONS\\s*=\\s*List\\.of\\(([^;]*?)\\);", Pattern.DOTALL);

    private static final Pattern CSV_LADDER = Pattern.compile(
            "cashu\\.denominations\\\\\":\\\\\"([0-9,]+)");

    @Test
    @DisplayName("all four copies list exactly the same denominations")
    void allCopiesAgree() throws IOException {
        Path root = repoRoot();
        Map<String, List<Integer>> ladders = new LinkedHashMap<>();

        ladders.put("VaultProvisioningOutboxHandler", javaLadder(root.resolve(
                "cashu-mint-admin/mint-admin-core/src/main/java/xyz/tcheeric/cashu/mint/"
                        + "admin/adapter/out/outbox/VaultProvisioningOutboxHandler.java")));
        ladders.put("MintPreloadDataGenerator", javaLadder(root.resolve(
                "cashu-mint-tools/src/main/java/xyz/tcheeric/cashu/mint/tools/"
                        + "MintPreloadDataGenerator.java")));
        ladders.put("keyset.properties", propertiesLadder(root.resolve(
                "cashu-mint-protocol/src/main/resources/keyset.properties")));
        ladders.put("ProvisionMint.java", csvLadder(root.resolve(
                "tools/provision-mint/ProvisionMint.java")));

        List<Integer> reference = ladders.get("MintPreloadDataGenerator");
        assertTrue(reference.size() >= 24,
                "precondition: the reference ladder should span 1..2^23, found "
                        + reference.size() + " entries — if this fails the test is reading "
                        + "the wrong thing rather than finding a real defect");

        ladders.forEach((name, ladder) -> assertEquals(reference, ladder,
                name + " lists different denominations from MintPreloadDataGenerator. "
                        + "A mint provisioned through one path would be unable to sign "
                        + "amounts the others handle, and every other test would still "
                        + "pass, because each only ever checks one copy."));
    }

    private static List<Integer> javaLadder(Path source) throws IOException {
        Matcher m = JAVA_LADDER.matcher(Files.readString(source));
        assertTrue(m.find(), "no DEFAULT_DENOMINATIONS = List.of(...) found in " + source
                + " — the declaration moved, and this check silently stopped covering it");
        return parseInts(m.group(1));
    }

    /**
     * The properties file writes each denomination as its own {@code key.N=N} line, so the
     * ladder is the set of N rather than a list literal.
     */
    private static List<Integer> propertiesLadder(Path source) throws IOException {
        List<Integer> amounts = new ArrayList<>();
        for (String line : Files.readAllLines(source)) {
            Matcher m = Pattern.compile("^key\\.(\\d+)\\s*=").matcher(line.trim());
            if (m.find()) {
                amounts.add(Integer.parseInt(m.group(1)));
            }
        }
        assertTrue(!amounts.isEmpty(), "no key.N entries found in " + source);
        amounts.sort(Integer::compareTo);
        return amounts;
    }

    private static List<Integer> csvLadder(Path source) throws IOException {
        Matcher m = CSV_LADDER.matcher(Files.readString(source));
        assertTrue(m.find(), "no cashu.denominations CSV found in " + source);
        return parseInts(m.group(1));
    }

    private static List<Integer> parseInts(String raw) {
        List<Integer> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.replaceAll("(?s)/\\*.*?\\*/", "").trim();
            if (!trimmed.isEmpty()) {
                values.add(Integer.parseInt(trimmed));
            }
        }
        return values;
    }
}
