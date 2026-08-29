package xyz.tcheeric.cashu.mint.proto.error;

import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;

/**
 * Reads a NUT-00 error body the way a test wants to talk about it.
 *
 * <p>The wire carries a numeric code, but a test reads better asserting on the code's name than
 * on a literal number, and a wrong number is far harder to spot in a failure message.
 */
public final class ErrorPayloads {

    private ErrorPayloads() {
    }

    /**
     * Returns the registry key for a numeric error code.
     *
     * @throws IllegalArgumentException if no code is registered, which means the mint emitted a
     *                                  code no client could interpret
     */
    public static String keyOf(int code) {
        return CashuErrorCode.forCode(code)
                .map(CashuErrorCode::name)
                .orElseThrow(() -> new IllegalArgumentException("no registered error code for " + code));
    }
}
