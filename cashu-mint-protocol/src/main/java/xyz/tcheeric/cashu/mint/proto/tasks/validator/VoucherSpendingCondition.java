package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.vault.db.log.SecretLogId;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.cashu.voucher.domain.VoucherMetadata;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

import java.util.UUID;

/**
 * Spending condition for voucher proofs.
 *
 * <p>This condition verifies voucher proofs using the standard Cashu BDHKE verification
 * (same as {@link RSSSpendingCondition}) plus additional voucher-specific validations:
 * <ul>
 *   <li>Expiry check: Rejects expired vouchers</li>
 *   <li>Issuer signature: Verifies the issuer's Schnorr signature on the voucher metadata</li>
 * </ul>
 *
 * <p>Unlike the previous implementation, this uses the standard keyset keys for BDHKE
 * verification. Voucher backing amounts are power-of-2 like regular Cashu tokens.
 * The voucher metadata (face value, issuer, etc.) is stored in the secret's NUT-10 tags
 * and does not affect the cryptographic key used for the proof.
 *
 * <p>Every collaborator is required. A condition without a mint cannot run the double-spend check,
 * which is scoped per mint, so that state is made unconstructible rather than merely caught at
 * verification time (cashu-mint#488).
 *
 * @param <T> the secret type (must be VoucherSecret)
 */
@AllArgsConstructor
@Slf4j
public class VoucherSpendingCondition<T extends Secret> implements SpendingCondition<T> {

    @Setter(AccessLevel.NONE)
    @NonNull
    private final Mint mint;
    @NonNull
    private final MintProtocolService mintProtocolService;
    @NonNull
    private final ProofVaultService proofVaultService;

    public VoucherSpendingCondition(@NonNull Mint mint,
                                    @NonNull MintProtocolService mintProtocolService) {
        this(mint, mintProtocolService, new DefaultProofVaultService());
    }

    @Override
    public void verify(@NonNull Proof<T> proof) throws CashuErrorException {
        log.debug("Verifying voucher proof: amount={}", proof.getAmount());

        Secret secret = proof.getSecret();

        // Read through VoucherMetadata rather than casting to VoucherSecret. Both voucher
        // kinds reach this condition -- VOUCHER directly, P2PK_VOUCHER via
        // P2PKVoucherSpendingCondition -- and a P2PKVoucherSecret is not a VoucherSecret, so a
        // cast yields null and every check below it degrades to a no-op. Guarding each check
        // on "did the cast work" is what made that silent: an expired P2PK_VOUCHER verified.
        boolean carriesVoucherMetadata =
                secret instanceof WellKnownSecret wks && VoucherMetadata.isVoucherCarrying(wks);
        WellKnownSecret voucherSecret = carriesVoucherMetadata ? (WellKnownSecret) secret : null;

        // 1. Validate voucher expiry
        if (voucherSecret != null && VoucherMetadata.isExpired(voucherSecret)) {
            log.error("voucher_expired voucherId={} expiresAt={}",
                    VoucherMetadata.voucherId(voucherSecret),
                    VoucherMetadata.expiresAt(voucherSecret));
                    throw new CashuErrorException(CashuErrorCode.voucher_expired,
                    "Voucher has expired and cannot be redeemed");
        }

        // 2. Validate issuer signature
        if (voucherSecret != null && VoucherMetadata.isSigned(voucherSecret)) {
            if (!VoucherSignatureService.verify(voucherSecret)) {
                log.error("voucher_signature_invalid voucherId={} issuerPubkey={}",
                        VoucherMetadata.voucherId(voucherSecret),
                        VoucherMetadata.issuerPublicKey(voucherSecret));
                        throw new CashuErrorException(CashuErrorCode.voucher_signature_invalid,
                        "Voucher issuer signature verification failed");
            }
            log.debug("Voucher issuer signature verified: voucherId={}",
                    VoucherMetadata.voucherId(voucherSecret));
        }

        // 3. Check if proof has been used already (double-spend prevention).
        //    Only STATE_SPENT is terminal — PENDING means another in-flight
        //    operation is still resolving and InvalidateProofsTask will
        //    re-invalidate idempotently from PENDING when the swap actually
        //    commits. Throwing here on PENDING would block legitimate retries
        //    and ignores the state machine that the storage path already
        //    encodes (see InvalidateProofsTask.storeAndInvalidateIdempotent).
        ProofEntity proofEntity;
        // Resolved before the try: the catch below deliberately treats a vault failure as
        // "no proof found" so verification can proceed, and a missing mint must not be absorbed
        // by that. Without a mint there is no double-spend check at all, and silently continuing
        // would let a spent proof verify.
        UUID mintId = requireMintId();
        try {
            proofEntity = proofVaultService.retrieveProof(mintId, ProofSecret.of(secret));
        } catch (CashuErrorException | RestClientException vaultUnavailable) {
            // Only a vault outage is absorbed, so that an outage does not block verification.
            // Anything else is a programming error on the one path where hiding it is least
            // acceptable: an NPE caught here once disabled the double-spend check (#486, #488).
            log.warn("voucher_proof_lookup_failed secret={} reason={}",
                    SecretLogId.of(secret.toString()), vaultUnavailable.toString());
            proofEntity = null;
        }

        if (proofEntity != null && ProofEntity.STATE_SPENT.equalsIgnoreCase(proofEntity.getState())) {
            log.error("verify_proof_already_used_error voucher_proof amount={} state={}",
                    proof.getAmount(), proofEntity.getState());
                    throw new CashuErrorException(CashuErrorCode.verify_proof_already_used_error);
        }

        if (proofEntity != null) {
            log.debug("Voucher proof exists in non-terminal state {} — allowing through for idempotent retry",
                    proofEntity.getState());
        } else {
            log.debug("Voucher proof has not been used...");
        }

        // 4. Validate keyset ID
        if (proof.getKeySetId() == null || proof.getKeySetId().isBlank()) {
            log.error("verify_proof_key_set_id_error");
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_id_error);
        }

        log.debug("Voucher proof keyset id is valid...");

        // 5. Get private key from keyset (standard power-of-2 key lookup)
        PrivateKey privateKey = getPrivateKey(proof);
        if (privateKey == null) {
            log.error("verify_proof_key_set_not_found amount={}", proof.getAmount());
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_not_found);
        }

        // 6. Verify BDHKE signature (same as RSSSpendingCondition)
        byte[] C = proof.getUnblindedSignature().getBytes();
        if (!BDHKEUtils.verify(secret.toString(), privateKey.toBytes(), C)) {
            log.error("verify_proof_failed_error voucher_proof amount={}", proof.getAmount());
            throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error);
        }

        log.info("voucher_proof_verified amount={} voucherId={}",
                proof.getAmount(),
                voucherSecret != null ? VoucherMetadata.voucherId(voucherSecret) : "unknown");
    }

    /**
     * Gets the private key for the proof amount using standard keyset lookup.
     *
     * @param proof the proof containing amount and keyset ID
     * @return the private key for the amount, or null if not found
     */
    private PrivateKey getPrivateKey(@NonNull Proof<T> proof) throws CashuErrorException {
        log.debug("Getting private key for voucher proof amount={}", proof.getAmount());
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

    /**
     * The mint whose proof table the double-spend check must consult.
     *
     * <p>Fails rather than returning null. The constructors reject a null mint, so this guards a
     * mint without an id, and stays as defence in depth that documents the invariant where it is
     * relied on. A proof lookup without a mint cannot answer "has this been spent here": an
     * unscoped lookup could read another mint's row, and skipping the lookup entirely would let an
     * already-spent proof verify.
     */
    private UUID requireMintId() throws CashuErrorException {
        if (mint.getId() == null) {
            log.error("verify_proof_no_mint_error voucher_proof: cannot check for a double spend "
                    + "without a mint, refusing to verify");
            throw new CashuErrorException(
                    "Cannot verify a voucher proof without a mint: the double-spend check is "
                            + "scoped per mint and cannot be skipped");
        }
        return UUID.fromString(mint.getId());
    }
}
