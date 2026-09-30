package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;

/**
 * NUT-20 quote locking, shared by regular and voucher mint quotes so both follow one rule.
 *
 * <p>An unlocked quote can be minted by anyone who learns its id, and a quote id travels through
 * logs, webhooks and traces. A locked quote is minted only against a BIP-340 signature from the
 * key it was locked to.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/20.md">NUT-20</a>
 */
@Slf4j
public final class MintQuoteLock {

    /** NUT-20 locks to a 33-byte compressed secp256k1 key, hex-encoded. */
    private static final int COMPRESSED_KEY_HEX_LENGTH = 66;

    private MintQuoteLock() {
    }

    /**
     * The key a new quote is locked to, or null when the wallet asked for an unlocked quote.
     *
     * <p>A key the mint could never verify a signature against is refused with 20009 here, when
     * the quote is requested. Accepting it would create a quote nobody can ever mint once paid.
     *
     * @param requestedKey the {@code pubkey} from the quote request, possibly null or blank
     * @throws CashuErrorException {@code 20009} when the key is not a compressed secp256k1 point
     */
    public static String lockingKey(String requestedKey) throws CashuErrorException {
        if (isUnlocked(requestedKey)) {
            return null;
        }
        if (requestedKey.length() != COMPRESSED_KEY_HEX_LENGTH || !isOnCurve(requestedKey)) {
            throw new CashuErrorException(CashuErrorCode.pubkey_required_for_mint_quote,
                    "pubkey must be a 33-byte compressed secp256k1 public key, hex-encoded");
        }
        return requestedKey;
    }

    /**
     * Refuses to issue against a locked quote without a valid signature from its key.
     *
     * <p>An unlocked quote passes straight through, which is NUT-04's behaviour. For a locked one
     * a missing and a wrong signature are the same failure to NUT-20 ("no valid signature"), so
     * both are {@code 20008}.
     *
     * @param lockingKey the key the quote is locked to, or null when it is unlocked
     * @param quoteId    the quote being minted, which the signature commits to
     * @param outputs    the outputs being minted, in request order, which the signature commits to
     * @param signature  the {@code signature} from the mint request, possibly null
     * @throws CashuErrorException {@code 20008} when the quote is locked and the signature is
     *                             missing or does not verify
     */
    public static void requireUnlockedBy(String lockingKey,
                                         String quoteId,
                                         List<BlindedMessage> outputs,
                                         String signature) throws CashuErrorException {
        if (isUnlocked(lockingKey)) {
            return;
        }
        if (signature == null || signature.isBlank()) {
            log.warn("mint_quote_lock nut20_signature_missing");
            throw new CashuErrorException(CashuErrorCode.mint_signature_invalid,
                    "Mint quote is locked to a pubkey and the request carries no signature");
        }
        if (!MintQuoteSignature.isValid(quoteId, outputs, lockingKey, signature)) {
            log.warn("mint_quote_lock nut20_signature_invalid");
            throw new CashuErrorException(CashuErrorCode.mint_signature_invalid);
        }
    }

    private static boolean isUnlocked(String key) {
        return key == null || key.isBlank();
    }

    private static boolean isOnCurve(String hex) {
        try {
            PublicKey.fromString(hex);
            return true;
        } catch (RuntimeException notAPoint) {
            return false;
        }
    }
}
