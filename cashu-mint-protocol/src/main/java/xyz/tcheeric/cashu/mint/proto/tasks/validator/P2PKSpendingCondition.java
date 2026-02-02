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

        verifyMultisig(proof);
        verifyLockTime(proof);
        verifyRefundPublicKey(proof);
    }

    private void verifyMultisig(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        log.debug("verifyMultisig {}", proof);
        P2PKSecret secret = proof.getSecret();
        int n_sigs = secret.getNSigs() > 0 ? secret.getNSigs() : 1;

        if (n_sigs < 0) {
            log.error("Invalid number of signatures");
            throw new IllegalArgumentException("Invalid number of signatures");
        }

        // Retrieve all signing public keys
        List<String> secretPubkeys = secret.getPubKeys();
        int estimatedSize = 1 + (secretPubkeys != null ? secretPubkeys.size() : 0);
        List<String> publicKeyList = new ArrayList<>(estimatedSize);
        publicKeyList.add(Hex.toHexString(secret.getData()));
        if (secretPubkeys != null) {
            publicKeyList.addAll(secretPubkeys);
        }

        // Retrieve all signatures for this proof
        List<String> signatures = proof.getWitness().getSignatures();

        // Count how many valid signatures
        byte[] data = proof.getSecret().toString().getBytes();
        int validSignatureCount = getValidSignatureCount(publicKeyList, signatures, data);

        // If the number of valid signatures is greater or equal to the number specified in n_sigs, the transaction is valid.
        if (validSignatureCount < n_sigs) {
            log.error("verify_invalid_number_of_signatures");
            ErrorResponse error = new ErrorResponse("verify_invalid_number_of_signatures");
            throw new CashuErrorException(error.toJson());
        }

        log.info("Multisig Verification passed");
    }

    private void verifyLockTime(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {
        log.debug("verifyLockTime {}", proof);
        int lockTime = proof.getSecret().getLockTime();

        if (lockTime <= 0) {
            // no lock time present
            return;
        }

        // If the tag locktime is the unix time and the mint's local clock is greater than locktime, the Proof becomes spendable
        if (lockTime < System.currentTimeMillis() / 1000) {
            log.info("Locktime verification passed");
            return;
        }

        log.error("verify_locktime_not_reached");
        ErrorResponse error = new ErrorResponse("verify_locktime_not_reached");
        throw new CashuErrorException(error.toJson());
    }

    private void verifyRefundPublicKey(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        log.debug("verifyRefundPublicKey {}", proof);
        int lockTime = proof.getSecret().getLockTime();

        // If the locktime is in the past...
        if (lockTime > 0 && lockTime < System.currentTimeMillis() / 1000) {

            List<String> refundPublicKeys = proof.getSecret().getRefund();

            if (refundPublicKeys != null && !refundPublicKeys.isEmpty()) {

                byte[] secretBytes = proof.getSecret().toString().getBytes();
                List<String> signatures = proof.getWitness().getSignatures();
                String sigFlag = proof.getSecret().getSigFlag();

                int validSignatureCount = getValidSignatureCount(refundPublicKeys, signatures, secretBytes);

                if (validSignatureCount == 0) {
                    rejectInvalidRefundSignature("proof_refund_signature");
                }

                if (P2PKSecret.SignatureFlag.valueOf(sigFlag).ordinal() >= 1) {
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
                        validSignatureCount = getValidSignatureCount(refundPublicKeys, outSigs, outData);
                        if (validSignatureCount == 0) {
                            rejectInvalidRefundSignature("output_witness_signature");
                        }
                    }
                }

                log.info("Refund Verification passed");

            } else {
                log.info("No refund public keys found. Skipping refund verification.");
            }
        } else {
            log.info("Locktime is in the future. Skipping refund verification. {}", proof);
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
