package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;

/**
 * Service for storing and retrieving blind signatures for wallet recovery (NUT-09).
 *
 * <p>The signature vault is a key-value store that maps blinded messages to their
 * corresponding blind signatures. This enables wallet recovery by allowing wallets
 * to retrieve previously issued signatures.
 *
 * <h2>Storage Strategy</h2>
 * <p>Signatures are stored using the blinded message as the key. The blinded message
 * is deterministic - it depends only on:
 * <ul>
 *   <li>The secret (random or derived via NUT-13)</li>
 *   <li>The blinding factor (random or derived via NUT-13)</li>
 *   <li>The mint's public key</li>
 * </ul>
 *
 * <h2>NUT-13 Compatibility</h2>
 * <p>This design naturally supports NUT-13 deterministic recovery without requiring
 * any code changes:
 * <ol>
 *   <li><b>During Minting:</b> Wallet derives secret + blinding factor from mnemonic</li>
 *   <li>Wallet creates blinded message from deterministic values</li>
 *   <li>Mint stores signature keyed by blinded message</li>
 *   <li><b>During Recovery:</b> Wallet derives same secret + blinding factor</li>
 *   <li>Wallet creates same blinded message (deterministic)</li>
 *   <li>Mint retrieves stored signature using blinded message as key</li>
 * </ol>
 *
 * <p><b>Key Insight:</b> The vault doesn't need to know whether secrets are random
 * or deterministic. It only stores the mapping: {@code BlindedMessage → BlindSignature}.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/09.md">NUT-09 Specification</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/13.md">NUT-13 Deterministic Secrets</a>
 */
public interface SignatureVaultService {

    /**
     * Records a blind signature the mint has just issued.
     *
     * <p>The signature is stored using the blinded message as the key. This allows
     * wallets to retrieve the signature later by presenting the same blinded message.
     *
     * <p>A blinded message is signed at most once. Storing a second signature under a
     * blinded message the vault already holds fails with
     * {@link xyz.tcheeric.cashu.common.nut00.CashuErrorCode#outputs_already_signed}
     * and leaves the first record untouched. This is the backstop behind the
     * pre-signing check in {@code ValidateTransactionTask}: two requests racing with
     * the same output both pass that check, and only one of them may record a
     * signature. Idempotent retries never reach this method; NUT-19 mint replays are
     * answered from the issuance record without re-signing.
     *
     * <p><b>NUT-13 Note:</b> When wallets use deterministic secrets (NUT-13), the
     * same mnemonic + derivation path will produce the same blinded message, enabling
     * reliable recovery.
     *
     * @param message   the blinded message to use as storage key
     * @param signature the blind signature to store
     * @param source    the operation that issued the signature
     * @throws CashuErrorException {@code outputs_already_signed} when the blinded message
     *                             was signed before, or another code if storage fails
     */
    void store(BlindedMessage message, BlindSignature signature, SignatureSource source)
            throws CashuErrorException;

    /**
     * Retrieves a previously stored blind signature for wallet recovery.
     *
     * <p>Looks up the signature using the blinded message as the key. Returns
     * {@code null} if no signature is found for the given blinded message.
     *
     * <p><b>NUT-13 Recovery:</b> During deterministic recovery, wallets derive
     * secrets and create blinded messages from a mnemonic. This method returns
     * the stored signature if the blinded message was previously minted, or
     * {@code null} if there's a gap in the derivation sequence.
     *
     * @param message the blinded message to look up
     * @return the stored blind signature, or {@code null} if not found
     * @throws CashuErrorException if retrieval fails
     */
    BlindSignature retrieve(BlindedMessage message) throws CashuErrorException;

    /**
     * Whether the record survives a restart and is shared by every mint instance on
     * the same database.
     *
     * <p>A vault that answers {@code false} forgets every signed output when the
     * process stops, which re-opens double signing and makes NUT-09 restore return
     * nothing. Production profiles refuse to boot with one.
     *
     * @return {@code true} only for a persistent, shared store
     */
    boolean isDurable();
}
