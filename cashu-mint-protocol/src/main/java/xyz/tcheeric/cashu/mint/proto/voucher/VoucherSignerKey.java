package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * A secp256k1 public key in the one form voucher signer keys are compared in: lower-case x-only
 * hex, 64 characters.
 *
 * <p>Vouchers carry their {@code issuer_pubkey} x-only, because BIP-340 verification takes an
 * x-only key. Operators, and other tools, often write the same key compressed, with a
 * {@code 02} or {@code 03} prefix. BIP-340 treats both parities of one x coordinate as the same
 * key, so dropping the prefix loses nothing a signature check could tell apart, and comparing the
 * two forms as strings would refuse a key that is in fact the configured one.
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class VoucherSignerKey {

    private static final int X_ONLY_HEX_LENGTH = 64;
    private static final int COMPRESSED_HEX_LENGTH = 66;

    private final String xOnlyHex;

    /**
     * Normalises a configured key, refusing anything that is not one.
     *
     * @param hex x-only or compressed hex, either case
     * @return the normalised key
     * @throws IllegalArgumentException when the value is not a 32-byte x-only or 33-byte
     *                                  compressed public key in hex
     */
    public static VoucherSignerKey parse(@NonNull String hex) {
        return tryParse(hex).orElseThrow(() -> new IllegalArgumentException(
                "Not a secp256k1 public key (expected 64 hex x-only or 66 hex compressed): "
                        + hex));
    }

    /**
     * Normalises a key presented by a voucher, which is untrusted input.
     *
     * @param hex the presented key, possibly null or malformed
     * @return the normalised key, or empty when the value is not a key
     */
    public static Optional<VoucherSignerKey> tryParse(String hex) {
        if (hex == null) {
            return Optional.empty();
        }
        String lower = hex.trim().toLowerCase(Locale.ROOT);
        if (!isHex(lower)) {
            return Optional.empty();
        }
        if (lower.length() == X_ONLY_HEX_LENGTH) {
            return Optional.of(new VoucherSignerKey(lower));
        }
        if (lower.length() == COMPRESSED_HEX_LENGTH && hasCompressedPrefix(lower)) {
            return Optional.of(new VoucherSignerKey(lower.substring(2)));
        }
        return Optional.empty();
    }

    /**
     * A short, non-reversible-in-practice label for log lines: the first twelve hex characters.
     *
     * @return the key's prefix
     */
    public String preview() {
        return xOnlyHex.substring(0, 12);
    }

    private static boolean hasCompressedPrefix(String hex) {
        return hex.startsWith("02") || hex.startsWith("03");
    }

    private static boolean isHex(String value) {
        if (value.isEmpty() || value.length() % 2 != 0) {
            return false;
        }
        try {
            HexFormat.of().parseHex(value);
            return true;
        } catch (IllegalArgumentException notHex) {
            return false;
        }
    }

    @Override
    public String toString() {
        return xOnlyHex;
    }
}
