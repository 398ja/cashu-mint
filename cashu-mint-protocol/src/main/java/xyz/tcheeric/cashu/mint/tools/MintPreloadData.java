package xyz.tcheeric.cashu.mint.tools;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * DTO describing the mint preload data required to seed the database.
 */
public record MintPreloadData(UUID mintId,
                              String keySetId,
                              UUID keySetRowId,
                              String unit,
                              List<DenominationKey> keys) {

    public MintPreloadData {
        Objects.requireNonNull(mintId, "mintId");
        Objects.requireNonNull(keySetId, "keySetId");
        Objects.requireNonNull(keySetRowId, "keySetRowId");
        Objects.requireNonNull(unit, "unit");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }

    public record DenominationKey(UUID id, int amount, String privateKeyHex) {
        public DenominationKey {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(privateKeyHex, "privateKeyHex");
        }
    }
}
