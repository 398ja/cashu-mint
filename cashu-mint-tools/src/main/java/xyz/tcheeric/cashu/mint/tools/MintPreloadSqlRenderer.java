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

        // Safely truncate if tables exist (no-op on fresh DBs)
        String[] tablesToTruncate = new String[] {
                "t_key_a", "t_keyset_a", "t_proof_a", "t_mint_a",
                "t_key", "t_keyset", "t_proof", "t_mint", "revinfo"
        };
        for (String table : tablesToTruncate) {
            sb.append("DO $$ BEGIN IF to_regclass('")
                    .append(table)
                    .append("') IS NOT NULL THEN TRUNCATE TABLE ")
                    .append(table)
                    .append(" RESTART IDENTITY CASCADE; END IF; END $$;")
                    .append(newline);
        }
        sb.append(newline);

        // Add optional column only when t_proof exists
        sb.append("DO $$ BEGIN IF to_regclass('t_proof') IS NOT NULL THEN ")
                .append("ALTER TABLE t_proof ADD COLUMN IF NOT EXISTS unblinded_signature VARCHAR(255);")
                .append(" END IF; END $$;")
                .append(newline).append(newline);

        // Upserts guarded by table existence checks
        sb.append("DO $$ BEGIN IF ")
                .append("to_regclass('t_mint') IS NOT NULL AND to_regclass('t_keyset') IS NOT NULL AND to_regclass('t_key') IS NOT NULL ")
                .append("THEN").append(newline);

        sb.append(indent).append("INSERT INTO t_mint AS target (id, archived, created_at, updated_at, version)").append(newline)
                .append(indent).append("VALUES ('").append(data.mintId()).append("'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)")
                .append(newline)
                .append(indent).append("ON CONFLICT (id) DO UPDATE").append(newline)
                .append(indent).append("SET archived = EXCLUDED.archived,").append(newline)
                .append(indent).append(indent).append("updated_at = EXCLUDED.updated_at,").append(newline)
                .append(indent).append(indent).append("version = EXCLUDED.version;").append(newline).append(newline);

        sb.append(indent).append("INSERT INTO t_keyset AS target (id, archived, created_at, updated_at, version, key_set_id, unit, mint_id)")
                .append(newline)
                .append(indent).append("VALUES ('").append(data.keySetRowId()).append("'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, '")
                .append(data.keySetId()).append("', '").append(data.unit()).append("', '")
                .append(data.mintId()).append("'::uuid)").append(newline)
                .append(indent).append("ON CONFLICT (id) DO UPDATE").append(newline)
                .append(indent).append("SET archived = EXCLUDED.archived,").append(newline)
                .append(indent).append(indent).append("updated_at = EXCLUDED.updated_at,").append(newline)
                .append(indent).append(indent).append("version = EXCLUDED.version,").append(newline)
                .append(indent).append(indent).append("key_set_id = EXCLUDED.key_set_id,").append(newline)
                .append(indent).append(indent).append("unit = EXCLUDED.unit,").append(newline)
                .append(indent).append(indent).append("mint_id = EXCLUDED.mint_id;").append(newline).append(newline);

        sb.append(indent).append("INSERT INTO t_key AS target (id, archived, created_at, updated_at, version, amount, private_key, key_set_id)")
                .append(newline)
                .append(indent).append("VALUES").append(newline);

        List<MintPreloadData.DenominationKey> keys = data.keys();
        for (int i = 0; i < keys.size(); i++) {
            MintPreloadData.DenominationKey key = keys.get(i);
            sb.append(indent).append(indent)
                    .append("('").append(key.id()).append("'::uuid, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ")
                    .append(key.amount()).append(", '").append(key.privateKeyHex()).append("', '")
                    .append(data.keySetRowId()).append("'::uuid)");
            if (i < keys.size() - 1) {
                sb.append(",");
            }
            sb.append(newline);
        }

        sb.append(indent).append("ON CONFLICT (id) DO UPDATE").append(newline)
                .append(indent).append("SET archived = EXCLUDED.archived,").append(newline)
                .append(indent).append(indent).append("updated_at = EXCLUDED.updated_at,").append(newline)
                .append(indent).append(indent).append("version = EXCLUDED.version,").append(newline)
                .append(indent).append(indent).append("amount = EXCLUDED.amount,").append(newline)
                .append(indent).append(indent).append("private_key = EXCLUDED.private_key,").append(newline)
                .append(indent).append(indent).append("key_set_id = EXCLUDED.key_set_id;").append(newline).append(newline);

        sb.append("END IF; END $$;").append(newline);
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
        String inputArg = args.length > 0 ? args[0] : null;
        Path input = (inputArg == null || inputArg.isBlank())
                ? Path.of("scripts/preload-test-data.json")
                : Path.of(inputArg);
        String outputArg = args.length > 1 ? args[1] : null;
        Path output = (outputArg == null || outputArg.isBlank())
                ? Path.of("scripts/preload-test-data.sql")
                : Path.of(outputArg);

        MintPreloadData data = readJson(input);
        writeSql(data, output);

        System.out.printf("Wrote preload SQL for mint %s and keyset %s to %s%n", data.mintId(), data.keySetId(),
                output.toAbsolutePath());
    }
}
