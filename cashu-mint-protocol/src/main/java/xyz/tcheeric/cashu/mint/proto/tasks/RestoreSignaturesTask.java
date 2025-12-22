package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.ArrayList;
import java.util.List;

/**
 * Task to restore blind signatures for wallet recovery (NUT-09).
 *
 * <p>This task iterates through blinded messages in the restore request and looks up
 * matching signatures in the signature vault. It returns only the messages that have
 * stored signatures, allowing wallets to recover their proofs.
 *
 * <h2>NUT-13 Compatibility</h2>
 * <p>This task is fully compatible with NUT-13 deterministic secret derivation:
 * <ul>
 *   <li>Deterministic secrets produce deterministic blinded messages</li>
 *   <li>Same secret + same blinding factor = same blinded message</li>
 *   <li>Signature vault lookup works identically for random and deterministic secrets</li>
 * </ul>
 *
 * <h2>Recovery Flow</h2>
 * <ol>
 *   <li>Wallet derives secrets (random or deterministic via NUT-13)</li>
 *   <li>Wallet creates blinded messages from secrets</li>
 *   <li>Wallet sends {@link PostRestoreRequest} with blinded messages</li>
 *   <li>This task looks up each blinded message in {@link SignatureVaultService}</li>
 *   <li>Returns {@link PostRestoreResponse} with matched outputs and signatures</li>
 *   <li>Wallet unblinds signatures to recover proofs</li>
 * </ol>
 *
 * <p><b>Gap Handling:</b> If a blinded message is not found (returns {@code null}),
 * it is skipped and not included in the response. This allows wallets to detect gaps
 * in counter sequences when using NUT-13 recovery.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/09.md">NUT-09 Specification</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/13.md">NUT-13 Deterministic Secrets</a>
 */
@Slf4j
public class RestoreSignaturesTask extends InstrumentedTask<PostRestoreResponse> {

    private final PostRestoreRequest request;
    private final SignatureVaultService signatureVaultService;

    /**
     * Creates a new restore signatures task.
     *
     * @param request                restore request with blinded messages to look up
     * @param signatureVaultService  signature vault for retrieving stored signatures
     */
    public RestoreSignaturesTask(@NonNull PostRestoreRequest request,
                                 @NonNull SignatureVaultService signatureVaultService) {
        this.request = request;
        this.signatureVaultService = signatureVaultService;
    }

    /**
     * Executes the signature restore operation.
     *
     * <p>For each blinded message in the request:
     * <ol>
     *   <li>Look up signature in vault using {@link SignatureVaultService#retrieve}</li>
     *   <li>If found, add both the blinded message and signature to response</li>
     *   <li>If not found (null), skip and continue (indicates gap in recovery)</li>
     * </ol>
     *
     * <p><b>NUT-13 Note:</b> During deterministic recovery, wallets send batches
     * of 100 blinded messages. Empty responses (no matches) indicate gaps in the
     * derivation sequence. Wallets should continue until 3 consecutive empty batches.
     *
     * @return restore response containing matched outputs and signatures
     * @throws CashuErrorException if signature retrieval fails
     */
    @Override
    protected PostRestoreResponse doExecute() throws CashuErrorException {
        int requestedCount = request.getBlindedMessages().size();
        log.debug("NUT-09 restore request received: {} blinded messages", requestedCount);

        List<BlindedMessage> outputs = new ArrayList<>();
        List<BlindSignature> signatures = new ArrayList<>();
        int foundCount = 0;
        int notFoundCount = 0;

        for (BlindedMessage bm : request.getBlindedMessages()) {
            BlindSignature sig = signatureVaultService.retrieve(bm);
            if (sig != null) {
                outputs.add(bm);
                signatures.add(sig);
                foundCount++;
            } else {
                notFoundCount++;
            }
        }

        log.info("NUT-09 restore completed: {}/{} signatures found (matches={}, gaps={})",
                foundCount, requestedCount, foundCount, notFoundCount);

        if (foundCount > 0) {
            log.debug("NUT-09 recovered signatures for keysets: {}",
                    signatures.stream()
                            .map(sig -> sig.getKeySetId().toString())
                            .distinct()
                            .toList());
        }

        return new PostRestoreResponse(outputs, signatures);
    }
}
