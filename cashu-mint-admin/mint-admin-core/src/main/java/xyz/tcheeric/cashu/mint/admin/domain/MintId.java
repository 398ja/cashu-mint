package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import java.util.UUID;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable identifier for a mint.
 */
@Value
@Accessors(fluent = true)
public class MintId {

    UUID value;

    private MintId(final UUID value) {
        this.value = Objects.requireNonNull(value, "mint identifier must not be null");
    }

    public static MintId of(final UUID value) {
        return new MintId(value);
    }

    public static MintId fromString(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("mint identifier must not be blank");
        }
        try {
            return of(UUID.fromString(raw));
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("mint identifier must be a valid UUID", ex);
        }
    }

    public String asString() {
        return value.toString();
    }
}
