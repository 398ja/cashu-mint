package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

/**
 * The one question every input must answer: did this mint actually issue it?
 *
 * <p>BDHKE verification is what separates a proof the mint signed from 32 bytes an attacker
 * invented. It is not a spending condition — a spending condition asks "may <em>you</em> spend
 * this?", which only means anything once the proof is known to be real.
 *
 * <h2>Why this is a separate, centrally-invoked class</h2>
 *
 * <p>It used to live inside the spending conditions, duplicated in {@code RSSSpendingCondition}
 * and {@code VoucherSpendingCondition}. {@code P2PKSpendingCondition} did not have it, because a
 * P2PK condition is about the witness signature and nothing in its name suggests it should also
 * be checking the mint's own signature. So a plain P2PK input on {@code /v1/swap} was never
 * checked against any keyset key: an attacker could lock a secret to a key they controlled, put
 * arbitrary bytes in {@code C}, sign the witness correctly, and the mint would sign real outputs
 * against a forged input. Unlimited issuance with no deposit.
 *
 * <p>Structuring it as "every condition remembers to do this" is what failed. Structuring it as
 * "the task does this to every proof, before it dispatches" cannot fail the same way: a new
 * spending condition can only add requirements, never remove this one.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/00.md">NUT-00</a>
 */
@Slf4j
public final class ProofAuthenticity {

    private final Mint mint;
    private final MintProtocolService mintProtocolService;

    public ProofAuthenticity(@NonNull Mint mint, @NonNull MintProtocolService mintProtocolService) {
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
    }

    /**
     * Requires that the proof carries a valid unblinded signature from this mint's keyset key for
     * its amount.
     *
     * @param proof the input to check
     * @throws CashuErrorException if the keyset is unusable or the signature does not verify
     */
    public <T extends Secret> void require(@NonNull Proof<T> proof) throws CashuErrorException {
        Secret secret = proof.getSecret();
        if (secret == null || proof.getUnblindedSignature() == null) {
            log.error("verify_proof_failed_error missing_secret_or_signature secretPresent={} signaturePresent={}",
                    secret != null, proof.getUnblindedSignature() != null);
            throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error);
        }

        if (proof.getKeySetId() == null || proof.getKeySetId().isBlank()) {
            log.error("verify_proof_key_set_id_error");
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_id_error);
        }

        PrivateKey privateKey =
                mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
        if (privateKey == null) {
            log.error("verify_proof_key_set_not_found amount={}", proof.getAmount());
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_not_found);
        }

        boolean valid;
        try {
            valid = BDHKEUtils.verify(secret.toString(), privateKey.toBytes(),
                    proof.getUnblindedSignature().getBytes());
        } catch (IllegalArgumentException | NullPointerException e) {
            // A malformed C decodes to nothing usable. That is a failed verification, not a 500.
            log.error("verify_proof_failed_error crypto_error amount={}", proof.getAmount(), e);
            throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error);
        }

        if (!valid) {
            log.error("verify_proof_failed_error amount={}", proof.getAmount());
            throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error);
        }
    }
}
