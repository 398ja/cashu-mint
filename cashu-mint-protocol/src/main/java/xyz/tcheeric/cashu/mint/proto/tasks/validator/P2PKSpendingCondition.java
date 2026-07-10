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

        // NUT-11: the primary n-of-m multisig (data + pubkeys tag) is a valid spend path at ANY time,
        // before or after the locktime. This is the escrow's normal 2-of-3 release.
        if (hasValidMultisig(proof)) {
            log.info("Multisig Verification passed");
            return;
        }

        // The primary path did not meet n_sigs. Before the locktime there is no other path.
        int lockTime = proof.getSecret().getLockTime();
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

        // Retrieve all signing public keys (the primary data key plus the pubkeys tag).
        List<String> secretPubkeys = secret.getPubKeys();
        int estimatedSize = 1 + (secretPubkeys != null ? secretPubkeys.size() : 0);
        List<String> publicKeyList = new ArrayList<>(estimatedSize);
        publicKeyList.add(Hex.toHexString(secret.getData()));
        if (secretPubkeys != null) {
            publicKeyList.addAll(secretPubkeys);
        }

        if (proof.getWitness() == null || proof.getWitness().getSignatures() == null) {
            return false;
        }
        List<String> signatures = proof.getWitness().getSignatures();
        byte[] data = secret.toString().getBytes();
        return getValidSignatureCount(publicKeyList, signatures, data) >= n_sigs;
    }

    /** Verify the refund path: the refund keys must sign (and, under SIG_ALL, the outputs too). */
    private void verifyRefundPath(@NonNull Proof<P2PKSecret> proof, @NonNull List<String> refundPublicKeys)
            throws CashuErrorException {

        log.debug("verifyRefundPath {}", proof);
        byte[] secretBytes = proof.getSecret().toString().getBytes();
        List<String> signatures = proof.getWitness() != null ? proof.getWitness().getSignatures() : null;
        String sigFlag = proof.getSecret().getSigFlag();

        if (signatures == null || getValidSignatureCount(refundPublicKeys, signatures, secretBytes) == 0) {
            rejectInvalidRefundSignature("proof_refund_signature");
        }

        if (sigFlag != null && P2PKSecret.SignatureFlag.valueOf(sigFlag).ordinal() >= 1) {
            if (blindedMessages == null || blindedMessages.isEmpty()) {
                log.error("BlindedMessage list is null or empty");
                throw new IllegalStateException("BlindedMessage list is null or empty");
            }

            for (BlindedMessage bm : blindedMessages) {
                if (bm.getWitness() == null) {
                    log.error("BlindedMessage witness is null");
                    throw new IllegalStateException("BlindedMessage witness is null");
                }
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

    private int getValidSignatureCount(List<String> publicKeyList, List<String> signatures, byte[] data) {
        int validSignatureCount = 0;

        for (int i = 0; i < publicKeyList.size(); i++) {
            String publicKey = publicKeyList.get(i);
            for (int j = 0; j < signatures.size(); j++) {
                String signature = signatures.get(j);
                try {
                    log.info("Verifying data: {} - publicKey: {} - signature: {}", Hex.toHexString(data), publicKey, signature);
                    if (Schnorr.verify(Utils.sha256(data), Hex.decode(publicKey), Hex.decode(signature))) {
                        validSignatureCount++;
                        log.info("Signature {} verified (Count: {})", signature, validSignatureCount);
                    }
                } catch (Exception e) {
                    log.warn("Error verifying signature. Continuing...", e);
                }
            }
        }
        return validSignatureCount;
    }
}
