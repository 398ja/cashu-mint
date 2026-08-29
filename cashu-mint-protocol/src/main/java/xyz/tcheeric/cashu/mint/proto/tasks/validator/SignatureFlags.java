package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

/**
 * NUT-11 signature flags.
 *
 * <p>Reading the flag is a parse of untrusted input, so it belongs in one place: an unrecognised
 * flag makes the secret malformed and the proof unspendable, which is a rejection, not a server
 * fault.
 */
@Slf4j
final class SignatureFlags {

    private SignatureFlags() {
    }

    /**
     * Whether the flag requests {@code SIG_ALL}. An absent flag means {@code SIG_INPUTS}, the
     * NUT-11 default.
     *
     * @throws CashuErrorException if the flag is present but not a recognised value
     */
    static boolean isSigAll(String sigFlag) throws CashuErrorException {
        if (sigFlag == null) {
            return false;
        }
        try {
            return P2PKSecret.SignatureFlag.valueOf(sigFlag) == P2PKSecret.SignatureFlag.SIG_ALL;
        } catch (IllegalArgumentException e) {
            log.error("invalid signature flag: {}", sigFlag);
            throw new CashuErrorException(CashuErrorCode.invalid_signature_flag);
        }
    }
}
