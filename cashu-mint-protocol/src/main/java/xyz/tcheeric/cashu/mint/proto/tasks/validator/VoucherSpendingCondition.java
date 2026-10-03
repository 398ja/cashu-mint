package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
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
 * <p>A verified issuer signature is then bound to the issuer it names through a
 * {@link VoucherIssuerBinding} (cashu-mint#527). The shorter constructors take the binding the
 * running mint installed in {@link MintIntegrityContext}.
 *
 * @param <T> the secret type (must be VoucherSecret)
 */
@Slf4j
public class VoucherSpendingCondition<T extends Secret> implements SpendingCondition<T> {

    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final ProofVaultService proofVaultService;
    private final VoucherIssuerBinding issuerBinding;

    public VoucherSpendingCondition(@NonNull Mint mint,
                                    @NonNull MintProtocolService mintProtocolService,
                                    @NonNull ProofVaultService proofVaultService,
                                    @NonNull VoucherIssuerBinding issuerBinding) {
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
        this.proofVaultService = proofVaultService;
        this.issuerBinding = issuerBinding;
    }

    public VoucherSpendingCondition(@NonNull Mint mint,
                                    @NonNull MintProtocolService mintProtocolService,
                                    @NonNull ProofVaultService proofVaultService) {
        this(mint, mintProtocolService, proofVaultService, MintIntegrityContext.voucherIssuerBinding());
    }

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

        // Where an unlocked voucher keeps its terms, and why the test is on TAGS.
        //
        // A P2PK_VOUCHER carries its terms as NUT-10 tags. A plain VOUCHER that arrived over
        // the wire carries them as CBOR inside `data`, with an EMPTY tag array, so every
        // tag-based check below found nothing and PASSED: no signature, no expiry, no issuer
        // binding (cashu-mint#525). The terms were there all along, unreadable to a tag reader.
        //
        // Both forms reach this method, because `VoucherSecret` built in memory holds its terms
        // as tags and puts the voucher ID in `data`. So the code has to decide which it is
        // looking at, and WHAT IT ASKS MATTERS.
        //
        // An earlier attempt asked about the shape of `data`: a value parsing as a UUID meant
        // "terms are in tags". A reviewer broke it in one move. `data` is attacker-chosen, so a
        // bare UUID plus an empty tag array took the tag path and sailed through every guard,
        // reproducing #525 exactly. `UUID.fromString` is lenient enough that even "1-1-1-1-1"
        // worked. A discriminator whose answer the attacker picks is not a boundary.
        //
        // The question asked instead is whether the secret has any TAGS, and that is sound for
        // a reason worth stating: the tags are inside what the issuer signed, so a forged tag
        // set fails verification, while an absent one cannot be faked into presence. An
        // unlocked voucher HAS no tags by construction, so empty tags means the terms must come
        // from the blob, and a blob that will not read is refused rather than waved through.
        WellKnownSecret checkable = voucherSecret;
        if (voucherSecret != null && voucherSecret.getKind() == WellKnownSecret.Kind.VOUCHER
                && isTagless(voucherSecret)) {
            VoucherSecret fromBlob = UnlockedVoucherBlob.read(voucherSecret);
            if (fromBlob == null) {
                log.error("voucher_blob_unreadable: refusing a voucher whose terms cannot be read");
                throw new CashuErrorException(CashuErrorCode.voucher_signature_invalid,
                        "Voucher terms could not be read");
            }
            checkable = fromBlob;
        }

        // 1. Validate voucher expiry
        if (checkable != null && VoucherMetadata.isExpired(checkable)) {
            log.error("voucher_expired voucherId={} expiresAt={}",
                    VoucherMetadata.voucherId(checkable),
                    VoucherMetadata.expiresAt(checkable));
                    throw new CashuErrorException(CashuErrorCode.voucher_expired,
                    "Voucher has expired and cannot be redeemed");
        }

        // 2. Validate the issuer signature, when the voucher carries one.
        //
        // Presence is deliberately NOT required here, and that is worth stating because it
        // looks like the hole #525 describes. It is not. An unsigned voucher is refused
        // wherever it is redeemed for value: the gateway's redemption path requires a
        // verified issuer signature, and the wallet refuses one too. What #525 was actually
        // about is that a SIGNED unlocked voucher's signature was never CHECKED, because the
        // signature lived in a blob no tag reader could see. That is what the decode above
        // fixes.
        //
        // Requiring presence here as well would refuse every unsigned voucher at the mint,
        // which sounds stricter and is a behaviour change well beyond this bug: the mint
        // would stop honouring proofs it has always honoured, and 8 existing tests say so,
        // including ones about expiry and double-spending that have nothing to do with
        // signatures. Widening the refusal is a separate decision, with its own issue.
        if (checkable != null && VoucherMetadata.isSigned(checkable)) {
            if (!VoucherSignatureService.verify(checkable)) {
                log.error("voucher_signature_invalid voucherId={} issuerPubkey={}",
                        VoucherMetadata.voucherId(checkable),
                        VoucherMetadata.issuerPublicKey(checkable));
                        throw new CashuErrorException(CashuErrorCode.voucher_signature_invalid,
                        "Voucher issuer signature verification failed");
            }
            log.debug("Voucher issuer signature verified: voucherId={}",
                    VoucherMetadata.voucherId(checkable));

            // 2b. A verified signature proves only that the key in issuer_pubkey signed, and that
            //     key is chosen by whoever built the voucher. The binding asks whether it is a
            //     key the mint trusts for the issuer the voucher names (cashu-mint#527). Only
            //     reached for a signature that verified, so it never reasons about a forged one,
            //     and never for an unsigned voucher: presence is a separate decision, see above.
            issuerBinding.requireTrustedSigner(checkable);
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
        ProofSecret proofSecret = ProofSecret.of(secret);
        try {
            proofEntity = proofVaultService.retrieveProof(mintId, proofSecret);
        } catch (CashuErrorException | RestClientException vaultUnavailable) {
            // Only a vault outage is absorbed, so that an outage does not block verification.
            // Anything else is a programming error on the one path where hiding it is least
            // acceptable: an NPE caught here once disabled the double-spend check (#486, #488).
            log.warn("voucher_proof_lookup_failed secret={} reason={}", proofSecret, vaultUnavailable.toString());
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

        // Logged from `checkable`, not from the raw secret, and that distinction is not
        // cosmetic. `VoucherMetadata.voucherId` falls back to the raw `data` bytes for an
        // unlocked voucher, so reading it off the wire secret printed the whole CBOR blob
        // into the log line: lock key, issuer signature and all. `checkable` names the
        // decoded voucher, whose id is an id.
        log.info("voucher_proof_verified amount={} voucherId={}",
                proof.getAmount(),
                checkable != null ? VoucherMetadata.voucherId(checkable) : "unknown");
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

    /**
     * Whether this secret carries no NUT-10 tags, so its terms can only be in a {@code data} blob.
     *
     * <p>This is the discriminator between the two forms a voucher secret arrives in, and it is
     * deliberately a question about TAGS rather than about {@code data}. The tags are covered by
     * the issuer signature, so a forged set fails verification and an absent set cannot be
     * faked into presence. {@code data} is covered by nothing before it is decoded, so routing
     * on its shape let an attacker choose the path: see the tests for a bare UUID in
     * {@code data} with empty tags, which reproduced cashu-mint#525 in full.
     */
    private static boolean isTagless(WellKnownSecret secret) {
        return secret.getTags() == null || secret.getTags().isEmpty();
    }
}
