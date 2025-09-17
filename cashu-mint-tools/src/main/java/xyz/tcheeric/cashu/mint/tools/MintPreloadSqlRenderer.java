package xyz.tcheeric.cashu.mint.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Utility that reads {@link MintPreloadData} from JSON and renders the SQL preload script that was
 * previously emitted by {@link MintPreloadDataGenerator}.
 */
public final class MintPreloadSqlRenderer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MintPreloadSqlRenderer() {
    }

    public static MintPreloadData readJson(Path input) throws IOException {
        Objects.requireNonNull(input, "input");
        return OBJECT_MAPPER.readValue(Files.readString(input, StandardCharsets.UTF_8), MintPreloadData.class);
    }

    public static String renderSql(MintPreloadData data) {
        Objects.requireNonNull(data, "data");
        String newline = System.lineSeparator();
        String indent = "    ";
        StringBuilder sb = new StringBuilder();

        sb.append("-- SQL preload generated from JSON mint data").append(newline);
        sb.append("-- Mint: ").append(data.mintId()).append(newline);
        sb.append("-- Keyset: ").append(data.keySetId()).append(newline).append(newline);

        sb.append("BEGIN;").append(newline).append(newline);
        sb.append("TRUNCATE TABLE").append(newline)
                .append(indent).append("t_key_a,").append(newline)
                .append(indent).append("t_keyset_a,").append(newline)
                .append(indent).append("t_proof_a,").append(newline)
                .append(indent).append("t_mint_a,").append(newline)
                .append(indent).append("t_key,").append(newline)
                .append(indent).append("t_keyset,").append(newline)
                .append(indent).append("t_proof,").append(newline)
                .append(indent).append("t_mint,").append(newline)
                .append(indent).append("revinfo").append(newline)
                .append("RESTART IDENTITY CASCADE;").append(newline).append(newline);

        sb.append("ALTER TABLE t_proof").append(newline)
                .append(indent).append("ADD COLUMN IF NOT EXISTS unblinded_signature VARCHAR(255);").append(newline)
                .append(newline);

        sb.append("INSERT INTO t_mint AS target (id, archived, created_at, updated_at, version)").append(newline)
                .append("VALUES ('").append(data.mintId()).append("'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)")
                .append(newline)
                .append("ON CONFLICT (id) DO UPDATE").append(newline)
                .append("SET archived = EXCLUDED.archived,").append(newline)
                .append(indent).append("updated_at = EXCLUDED.updated_at,").append(newline)
                .append(indent).append("version = EXCLUDED.version;").append(newline).append(newline);

        sb.append("INSERT INTO t_keyset AS target (id, archived, created_at, updated_at, version, key_set_id, unit, mint_id)")
                .append(newline)
                .append("VALUES ('").append(data.keySetRowId()).append("'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '")
                .append(data.keySetId()).append("', '").append(data.unit()).append("', '")
                .append(data.mintId()).append("'::uuid)").append(newline)
                .append("ON CONFLICT (id) DO UPDATE").append(newline)
                .append("SET archived = EXCLUDED.archived,").append(newline)
                .append(indent).append("updated_at = EXCLUDED.updated_at,").append(newline)
                .append(indent).append("version = EXCLUDED.version,").append(newline)
                .append(indent).append("key_set_id = EXCLUDED.key_set_id,").append(newline)
                .append(indent).append("unit = EXCLUDED.unit,").append(newline)
                .append(indent).append("mint_id = EXCLUDED.mint_id;").append(newline).append(newline);

        sb.append("INSERT INTO t_key AS target (id, archived, created_at, updated_at, version, amount, private_key, key_set_id)")
                .append(newline)
                .append("VALUES").append(newline);

        List<MintPreloadData.DenominationKey> keys = data.keys();
        for (int i = 0; i < keys.size(); i++) {
            MintPreloadData.DenominationKey key = keys.get(i);
            sb.append(indent)
                    .append("('").append(key.id()).append("'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ")
                    .append(key.amount()).append(", '").append(key.privateKeyHex()).append("', '")
                    .append(data.keySetRowId()).append("'::uuid)");
            if (i < keys.size() - 1) {
                sb.append(",");
            }
            sb.append(newline);
        }

        sb.append("ON CONFLICT (id) DO UPDATE").append(newline)
                .append("SET archived = EXCLUDED.archived,").append(newline)
                .append(indent).append("updated_at = EXCLUDED.updated_at,").append(newline)
                .append(indent).append("version = EXCLUDED.version,").append(newline)
                .append(indent).append("amount = EXCLUDED.amount,").append(newline)
                .append(indent).append("private_key = EXCLUDED.private_key,").append(newline)
                .append(indent).append("key_set_id = EXCLUDED.key_set_id;").append(newline).append(newline);

        sb.append("COMMIT;").append(newline);
        return sb.toString();
    }

    public static void writeSql(MintPreloadData data, Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, renderSql(data), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: MintPreloadSqlRenderer <input-json> [output-sql]");
        }
        Path input = Path.of(args[0]);
        Path output = args.length > 1 ? Path.of(args[1]) : Path.of("scripts/preload-test-data.sql");

        MintPreloadData data = readJson(input);
        writeSql(data, output);

        System.out.printf("Wrote preload SQL for mint %s and keyset %s to %s%n", data.mintId(), data.keySetId(),
                output.toAbsolutePath());
    }
}
