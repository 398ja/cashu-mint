package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import xyz.tcheeric.cashu.common.Witness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place a spend's witness is turned into what NUT-07 and NUT-17 put on the wire.
 *
 * <p>NUT-07 states the witness is the serialised JSON the spender sent, e.g.
 * {@code {"signatures":["<hex>"]}}, so a wallet can verify who spent a proof. The mint used to
 * persist {@code Witness.toString()} instead, a Lombok rendering
 * ({@code Witness(signatures=[<hex>])}) no wallet can parse, which made every spend-witness check
 * fail. New spends are stored as JSON ({@link #toJson}); rows written in the legacy form are
 * upgraded when read ({@link #toWire}), so already-spent proofs answer correctly without a data
 * migration that would have to touch every proof row.
 */
public final class WitnessJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lombok's rendering of {@link Witness}: {@code Witness(signatures=[a, b])} or {@code =null}. */
    private static final Pattern LEGACY = Pattern.compile("^Witness\\(signatures=(?:\\[([0-9a-fA-F,\\s]*)]|null)\\)$");

    private WitnessJson() {
    }

    /** Serialises a witness for storage, or {@code null} for none. */
    public static String toJson(Witness witness) {
        if (witness == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(witness);
        } catch (JsonProcessingException e) {
            // A list of strings always serialises; reaching here is a programming error.
            throw new IllegalStateException("witness is not serialisable", e);
        }
    }

    /**
     * Answers the stored witness as NUT-07 wire JSON. JSON passes through untouched, the legacy
     * Lombok form is rebuilt as JSON, and anything else is returned as stored rather than guessed
     * at, because a witness is evidence and must never be invented.
     */
    public static String toWire(String stored) {
        if (stored == null) {
            return null;
        }
        Matcher m = LEGACY.matcher(stored.trim());
        if (!m.matches()) {
            return stored;
        }
        List<String> signatures = new ArrayList<>();
        String inner = m.group(1);
        if (inner != null) {
            for (String part : inner.split(",")) {
                String sig = part.trim();
                if (!sig.isEmpty()) {
                    signatures.add(sig);
                }
            }
        }
        return toJson(new Witness(signatures));
    }
}
