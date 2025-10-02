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

class MintPreloadSqlRendererTest {

    /**
     * Confirms that SQL rendered from JSON contains the mint id, keyset id, and private keys.
     */
    @Test
    void renderSqlIncludesMintMetadata() throws IOException {
        UUID mintId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Function<Integer, String> privateKeySource = amount -> String.format("%064x", amount);

        MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, "sat", List.of(1, 2), privateKeySource);
        String json = generator.buildJson();
        Path tempJson = Files.createTempFile("mint-preload", ".json");
        Files.writeString(tempJson, json);

        MintPreloadData data = MintPreloadSqlRenderer.readJson(tempJson);
        String sql = MintPreloadSqlRenderer.renderSql(data);

        assertTrue(sql.contains(data.mintId().toString()));
        assertTrue(sql.contains(data.keySetId()));
        data.keys().forEach(key -> assertTrue(sql.contains(key.privateKeyHex())));
    }

    /**
     * Verifies that writeSql writes the rendered SQL to disk for downstream consumption.
     */
    @Test
    void writeSqlPersistsScript(@TempDir Path tempDir) throws IOException {
        UUID mintId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Function<Integer, String> privateKeySource = amount -> String.format("%064x", amount);

        MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, "sat", List.of(1, 2), privateKeySource);
        Path jsonPath = tempDir.resolve("preload.json");
        generator.writeJson(jsonPath);

        Path sqlPath = tempDir.resolve("preload.sql");
        MintPreloadData data = MintPreloadSqlRenderer.readJson(jsonPath);
        MintPreloadSqlRenderer.writeSql(data, sqlPath);

        String sql = Files.readString(sqlPath);
        assertTrue(sql.contains("INSERT INTO t_key"));
        assertEquals(MintPreloadSqlRenderer.renderSql(data), sql);
    }
}
