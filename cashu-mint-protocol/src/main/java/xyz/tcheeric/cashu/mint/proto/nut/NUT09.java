package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.RestoreSignaturesTask;

/**
 * NUT-09: Restore signatures (wallet recovery).
 *
 * <p>This implementation supports both random and deterministic (NUT-13) secret recovery.
 * The mint stores blind signatures keyed by blinded messages. When a wallet sends a restore
 * request with blinded messages, the mint returns any matching signatures it has stored.
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Wallet sends {@link PostRestoreRequest} with list of {@code BlindedMessage}s</li>
 *   <li>Mint looks up each blinded message in the {@link SignatureVaultService}</li>
 *   <li>Returns {@link PostRestoreResponse} with matching outputs and signatures</li>
 *   <li>Wallet unblinds signatures to recover {@code Proof}s</li>
 * </ol>
 *
 * <h2>NUT-13 Deterministic Recovery</h2>
 * <p>This implementation is fully compatible with NUT-13 deterministic secret derivation
 * without requiring any code changes. Here's how:
 *
 * <h3>Deterministic Secrets Produce Deterministic Blinded Messages</h3>
 * <ul>
 *   <li><b>During Minting:</b> Wallet derives secret from mnemonic using BIP32 path (NUT-13)</li>
 *   <li>Wallet creates blinded message from deterministic secret + blinding factor</li>
 *   <li>Mint stores signature keyed by blinded message hash</li>
 * </ul>
 *
 * <ul>
 *   <li><b>During Recovery:</b> Wallet derives same secret from same mnemonic + path</li>
 *   <li>Wallet creates same blinded message (deterministic)</li>
 *   <li>Mint retrieves stored signature for the blinded message</li>
 *   <li>Wallet unblinds signature to recover the proof</li>
 * </ul>
 *
 * <h3>Recovery Process</h3>
 * <p>Wallets implementing NUT-13 should:
 * <ol>
 *   <li>Derive secrets using BIP32 paths: {@code m/129372'/0'/{keyset_id_int}'/{counter}'/0}</li>
 *   <li>Create blinded messages from derived secrets</li>
 *   <li>Send restore requests in batches (recommended: 100 tokens per batch)</li>
 *   <li>Continue until 3 consecutive empty batches are returned</li>
 *   <li>Verify spent status using NUT-07 for recovered proofs</li>
 * </ol>
 *
 * <h3>Key Insight</h3>
 * <p>The mint doesn't need to know about derivation paths, mnemonics, or whether secrets
 * are random or deterministic. It only stores and retrieves signatures based on blinded
 * message hashes. This design naturally supports NUT-13 recovery while maintaining
 * backward compatibility with random secrets.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/09.md">NUT-09 Specification</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/13.md">NUT-13 Deterministic Secrets</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/07.md">NUT-07 Token State Check</a>
 * @see SignatureVaultService
 * @see RestoreSignaturesTask
 */
@Nut(value = 9, description = "Restore signatures")
public final class NUT09 {

    private NUT09() {
        // Utility class - prevent instantiation
    }

    /**
     * Restores blind signatures for the given blinded messages.
     *
     * <p>This method is the entry point for wallet recovery. It delegates to
     * {@link RestoreSignaturesTask} which looks up each blinded message in the
     * signature vault and returns matching signatures.
     *
     * <p><b>NUT-13 Compatibility:</b> Works seamlessly with deterministic secrets.
     * When wallets derive secrets deterministically from a mnemonic, they produce
     * the same blinded messages during recovery, allowing this method to return
     * the stored signatures.
     *
     * @param request        restore request containing blinded messages to look up
     * @param signatureVaultService service that stores blinded message → signature mappings
     * @return restore response containing matched outputs and signatures
     * @throws CashuErrorException if restoration fails
     *
     * @see PostRestoreRequest
     * @see PostRestoreResponse
     * @see SignatureVaultService#retrieve(xyz.tcheeric.cashu.common.BlindedMessage)
     */
    public static PostRestoreResponse restore(@NonNull PostRestoreRequest request,
                                              @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return new RestoreSignaturesTask(request, signatureVaultService).execute();
    }
}
