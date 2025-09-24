package xyz.tcheeric.cashu.mint.proto.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.common.Signature;

/**
 * Provides deterministic sample signatures for tests so they don't depend on random curve points.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SignatureTestData {

    private static final String SAMPLE_SIGNATURE_HEX =
            "03603b00ab28374d5e50936ad0b4c606b17d435671f65973e8b04f28d5987f8703";
    private static final String ALTERNATE_SIGNATURE_HEX =
            "02d908e2a5ce0a6ce6228667d4f33470e8308dce587a7f1d7b3114873d5d02fc77";

    public static Signature sampleSignature() {
        return Signature.fromString(SAMPLE_SIGNATURE_HEX);
    }

    public static Signature alternateSignature() {
        return Signature.fromString(ALTERNATE_SIGNATURE_HEX);
    }

    public static String sampleSignatureHex() {
        return SAMPLE_SIGNATURE_HEX;
    }

    public static String alternateSignatureHex() {
        return ALTERNATE_SIGNATURE_HEX;
    }
}
