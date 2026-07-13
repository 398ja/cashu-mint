package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor
@Slf4j
@Data
public class P2PKSpendingCondition implements SpendingCondition<P2PKSecret> {

    @Setter(AccessLevel.NONE)
    private List<BlindedMessage> blindedMessages;

    @Override
    public void verify(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        log.info("Verifying P2PK spending condition for {}", proof);
        P2PKSecret secret = proof.getSecret();

        // NUT-11: the primary n-of-m multisig (data + pubkeys tag) is a valid spend path at ANY time,
        // before or after the locktime. This is the escrow's normal 2-of-3 release.
        if (hasValidMultisig(proof)) {
            // NUT-11 SIG_ALL: when set, the mint's outputs must also be signed by the authorized keys.
            verifyOutputsUnderSigAll(proof, primaryPublicKeys(secret));
            log.info("Multisig Verification passed");
            return;
        }

        // The primary path did not meet n_sigs. Before the locktime there is no other path.
        int lockTime = secret.getLockTime();
        boolean locktimePassed = lockTime > 0 && lockTime < System.currentTimeMillis() / 1000;
        if (!locktimePassed) {
            log.error("verify_invalid_number_of_signatures");
            throw new CashuErrorException(new ErrorResponse("verify_invalid_number_of_signatures").toJson());
        }

        // After the locktime the refund keys may reclaim; absent refund keys the proof is unlocked.
        List<String> refundPublicKeys = proof.getSecret().getRefund();
        if (refundPublicKeys == null || refundPublicKeys.isEmpty()) {
            log.info("Locktime passed and no refund keys present — proof is unlocked");
            return;
        }
        verifyRefundPath(proof, refundPublicKeys);
        log.info("Refund Verification passed");
    }

    /** Whether the primary n-of-m multisig ({@code data} + {@code pubkeys} tag) is satisfied. */
    private boolean hasValidMultisig(@NonNull Proof<P2PKSecret> proof) {

        log.debug("verifyMultisig {}", proof);
        P2PKSecret secret = proof.getSecret();
        int n_sigs = secret.getNSigs() > 0 ? secret.getNSigs() : 1;

        if (proof.getWitness() == null || proof.getWitness().getSignatures() == null) {
            return false;
        }
        List<String> signatures = proof.getWitness().getSignatures();
        byte[] data = secret.toString().getBytes(StandardCharsets.UTF_8);
        return getValidSignatureCount(primaryPublicKeys(secret), signatures, data) >= n_sigs;
    }

    /** Whether the sig flag requests SIG_ALL. An unrecognized flag is a protocol error (rejected), not a 500. */
    private static boolean isSigAll(String sigFlag) throws CashuErrorException {
        if (sigFlag == null) {
            return false;
        }
        try {
            return P2PKSecret.SignatureFlag.valueOf(sigFlag).ordinal() >= 1;
        } catch (IllegalArgumentException e) {
            log.error("invalid signature flag: {}", sigFlag);
            throw new CashuErrorException(new ErrorResponse("invalid_signature_flag").toJson());
        }
    }

    /** SIG_ALL requires the outputs to be present; a missing/empty output set is a protocol error, not a 500. */
    private static void requireSigAllOutputs(List<BlindedMessage> outputs) throws CashuErrorException {
        if (outputs == null || outputs.isEmpty()) {
            log.error("SIG_ALL requires signed outputs but none were provided");
            throw new CashuErrorException(new ErrorResponse("output_witness_signature").toJson());
        }
    }

    /** SIG_ALL requires each output to carry a witness; a missing one is a protocol error, not a 500. */
    private static void requireOutputWitness(BlindedMessage bm) throws CashuErrorException {
        if (bm.getWitness() == null) {
            log.error("SIG_ALL output is missing its witness");
            throw new CashuErrorException(new ErrorResponse("output_witness_signature").toJson());
        }
    }

    /** The authorized primary public keys: the {@code data} key plus the {@code pubkeys} tag. */
    private static List<String> primaryPublicKeys(P2PKSecret secret) {
        List<String> tag = secret.getPubKeys();
        List<String> keys = new ArrayList<>(1 + (tag != null ? tag.size() : 0));
        keys.add(Hex.toHexString(secret.getData()));
        if (tag != null) {
            keys.addAll(tag);
        }
        return keys;
    }

    /**
     * NUT-11 SIG_ALL: when the sig flag is SIG_ALL, every output blinded message must also carry a
     * valid witness signature from the authorized keys. For SIG_INPUTS (the escrow default) this is a
     * no-op. Applied on the primary spend path (the refund path checks outputs separately).
     */
    private void verifyOutputsUnderSigAll(@NonNull Proof<P2PKSecret> proof, @NonNull List<String> authorizedKeys)
            throws CashuErrorException {
        if (!isSigAll(proof.getSecret().getSigFlag())) {
            return; // SIG_INPUTS — outputs are not constrained
        }
        requireSigAllOutputs(blindedMessages);
        for (BlindedMessage bm : blindedMessages) {
            requireOutputWitness(bm);
            byte[] outData = bm.getBlindedMessage().toBytes();
            if (getValidSignatureCount(authorizedKeys, bm.getWitness().getSignatures(), outData) == 0) {
                log.error("output_witness_signature");
                throw new CashuErrorException(new ErrorResponse("output_witness_signature").toJson());
            }
        }
    }

    /** Verify the refund path: the refund keys must sign (and, under SIG_ALL, the outputs too). */
    private void verifyRefundPath(@NonNull Proof<P2PKSecret> proof, @NonNull List<String> refundPublicKeys)
            throws CashuErrorException {

        log.debug("verifyRefundPath {}", proof);
        byte[] secretBytes = proof.getSecret().toString().getBytes(StandardCharsets.UTF_8);
        List<String> signatures = proof.getWitness() != null ? proof.getWitness().getSignatures() : null;
        String sigFlag = proof.getSecret().getSigFlag();

        if (signatures == null || getValidSignatureCount(refundPublicKeys, signatures, secretBytes) == 0) {
            rejectInvalidRefundSignature("proof_refund_signature");
        }

        if (isSigAll(sigFlag)) {
            requireSigAllOutputs(blindedMessages);

            for (BlindedMessage bm : blindedMessages) {
                requireOutputWitness(bm);
                List<String> outSigs = bm.getWitness().getSignatures();
                byte[] outData = bm.getBlindedMessage().toBytes();
                if (getValidSignatureCount(refundPublicKeys, outSigs, outData) == 0) {
                    rejectInvalidRefundSignature("output_witness_signature");
                }
            }
        }
    }

    private void rejectInvalidRefundSignature(String context) throws CashuErrorException {
        log.error("verify_invalid_refund_signature context={}", context);
        ErrorResponse error = new ErrorResponse("verify_invalid_refund_signature");
        throw new CashuErrorException(error.toJson());
    }

    /**
     * NUT-11: counts the number of <b>distinct public keys</b> (compared by lowercase x-coordinate)
     * that have at least one valid signature over {@code data} — NOT the number of matching
     * (key, signature) pairs. Because Schnorr signatures are non-deterministic, counting raw
     * signatures would let a single key satisfy an n-of-m threshold by submitting duplicate
     * signatures (or a crafted secret could repeat a pubkey); each key therefore contributes at most
     * one to the count.
     */
    private int getValidSignatureCount(List<String> publicKeyList, List<String> signatures, byte[] data) {
        if (publicKeyList == null || signatures == null) {
            return 0;
        }
        byte[] hash;
        try {
            hash = Utils.sha256(data);
        } catch (Exception e) {
            log.warn("Error hashing spend data. Rejecting signatures.", e);
            return 0;
        }
        java.util.Set<String> countedKeys = new java.util.HashSet<>();
        int validKeyCount = 0;

        for (String publicKey : publicKeyList) {
            if (publicKey == null || publicKey.isBlank()) {
                continue;
            }
            // Each distinct public key (by x-coordinate) is counted at most once.
            if (!countedKeys.add(xCoordinate(publicKey))) {
                continue;
            }
            for (String signature : signatures) {
                try {
                    if (Schnorr.verify(hash, Hex.decode(publicKey), Hex.decode(signature))) {
                        validKeyCount++;
                        break; // this key is satisfied by some signature; stop scanning signatures
                    }
                } catch (Exception e) {
                    log.warn("Error verifying signature. Continuing...", e);
                }
            }
        }
        return validKeyCount;
    }

    /** The lowercase x-coordinate of a public key (strips a 33-byte compressed {@code 02/03} prefix). */
    private static String xCoordinate(String publicKeyHex) {
        String hex = publicKeyHex.toLowerCase();
        if (hex.length() == 66 && (hex.startsWith("02") || hex.startsWith("03"))) {
            return hex.substring(2);
        }
        return hex;
    }
}
