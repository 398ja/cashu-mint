package xyz.tcheeric.cashu.mint.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MintPreloadDataGeneratorTest {

    /**
     * Ensures the generator populates the data object with the mint id, unit, and requested denominations.
     */
    @Test
    void dataIncludesMintKeysetAndDenominations() {
        UUID mintId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<Integer> denominations = List.of(8, 4, 2, 1);
        Function<Integer, String> privateKeySource = amount -> String.format("%064x", amount);

        MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, "sat", denominations, privateKeySource);
        MintPreloadData data = generator.data();

        assertEquals(mintId, data.mintId());
        assertEquals("sat", data.unit());
        assertEquals(denominations.size(), data.keys().size());
        assertTrue(data.keys().stream().anyMatch(key -> key.amount() == 1));
        assertTrue(data.keys().stream().anyMatch(key -> key.amount() == 8));
    }

    /**
     * Verifies that writeJson persists the generated JSON to the specified file.
     */
    @Test
    void writeJsonPersistsToDisk(@TempDir Path tempDir) throws IOException {
        UUID mintId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Function<Integer, String> privateKeySource = amount -> String.format("%064x", amount);

        MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, "sat", List.of(1, 2), privateKeySource);
        Path output = tempDir.resolve("preload.json");
        generator.writeJson(output);

        String expected = generator.buildJson();
        String fromDisk = Files.readString(output);
        assertEquals(expected, fromDisk);
    }
}
