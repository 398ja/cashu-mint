package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.model.BlindedMessage;
import xyz.tcheeric.cashu.common.model.P2PKSecret;
import xyz.tcheeric.cashu.common.model.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@AllArgsConstructor
@Log
@Data
public class P2PKSpendingCondition implements SpendingCondition<P2PKSecret> {

    @Setter(AccessLevel.NONE)
    private List<BlindedMessage> blindedMessages;

    @Override
    public void verify(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        log.log(Level.INFO, "Verifying P2PK spending condition for {0}", proof);

        verifyMultisig(proof);
        verifyLockTime(proof);
        verifyRefundPublicKey(proof);
    }

    private void verifyMultisig(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        P2PKSecret secret = proof.getSecret();
        int n_sigs = secret.getNSigs();

        if (n_sigs < 0) {
            throw new IllegalArgumentException("Invalid number of signatures");
        }

        // Is this a refund transaction?
        List<String> refundList = secret.getRefund();
        if (refundList != null && !refundList.isEmpty()) {
            log.log(Level.INFO, "Refund public keys: {0}. Skipping verification.", refundList);
            return;
        }

        // Is this a refund transaction?
        String sigFlag = proof.getSecret().getSigFlag();
        if (P2PKSecret.SignatureFlag.valueOf(sigFlag).ordinal() > 0) {
            log.log(Level.INFO, "Signature flag ordinal is greater than 0. This is a refund transaction. Skipping verification.");
            return;
        }

        // Retrieve all signing public keys
        List<String> publicKeyList = new ArrayList<>();
        publicKeyList.add(Hex.toHexString(secret.getData()));
        List<String> secretPubkeys = secret.getPubKeys();
        if (secretPubkeys != null) {
            secretPubkeys.stream().forEach(pubkey -> publicKeyList.add(pubkey));
        }

        // Retrieve all signatures
        List<String> signatures = proof.getWitness().getSignatures();

        // Count how many valid signatures
        byte[] data = proof.getSecret().getData();
        int validSignatureCount = getValidSignatureCount(publicKeyList, signatures, data);

        // If the number of valid signatures is greater or equal to the number specified in n_sigs, the transaction is valid.
        if (validSignatureCount < n_sigs) {
            throw new CashuErrorException("verify_invalid_number_of_signatures");
        }

        log.log(Level.INFO, "Multisig Verification passed");
    }

    private void verifyLockTime(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {
        int lockTime = proof.getSecret().getLockTime();

        // If the tag locktime is the unix time and the mint's local clock is greater than locktime, the Proof becomes spendable
        if (lockTime >= 0 && lockTime < System.currentTimeMillis() / 1000) {
            log.log(Level.INFO, "Locktime verification passed");
            return;
        }

        throw new CashuErrorException("verify_locktime_not_reached");
    }

    private void verifyRefundPublicKey(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        int lockTime = proof.getSecret().getLockTime();

        // If the locktime is in the past...
        if (lockTime > 0 && lockTime < System.currentTimeMillis() / 1000) {

            List<String> refundPublicKeys = proof.getSecret().getRefund();
            int validSignatureCount = 0;

            //... and a tag refund is present
            if (refundPublicKeys != null && refundPublicKeys.size() > 0) {

                byte[] data = proof.getSecret().getData();
                List<String> signatures = proof.getWitness().getSignatures();
                String sigFlag = proof.getSecret().getSigFlag();

                // ...the Proof is spendable only if a valid signature by one of the refund pubkeys is provided in Proof.witness.signatures
                if (P2PKSecret.SignatureFlag.valueOf(sigFlag).ordinal() >= 0) {

                    validSignatureCount = getValidSignatureCount(refundPublicKeys, signatures, data);
                }

                if (validSignatureCount == 0) {
                    throw new CashuErrorException("verify_invalid_refund_signature");
                }

                // ...and, depending on the signature flag, in BlindedMessage.witness.signatures.
                if (P2PKSecret.SignatureFlag.valueOf(sigFlag).ordinal() >= 1) {

                    if (blindedMessages == null && !blindedMessages.isEmpty()) {
                        throw new IllegalStateException("BlindedMessage list is null or empty");
                    }

                    signatures = blindedMessages.stream().flatMap(blindedMessage -> {
                        if (blindedMessage.getWitness() == null) {
                            throw new IllegalStateException("BlindedMessage witness is null");
                        }
                        return blindedMessage.getWitness().getSignatures().stream();
                    }).toList();

                    validSignatureCount = getValidSignatureCount(refundPublicKeys, signatures, data);
                }

                if (validSignatureCount == 0) {
                    throw new CashuErrorException("verify_invalid_refund_signature");
                }

            } else {
                log.log(Level.INFO, "No refund public keys found. Skipping refund verification.");
            }
        } else {
            log.log(Level.INFO, "Locktime is in the future. Skipping refund verification.", proof);
        }

        log.log(Level.INFO, "Refund Verification passed");
    }

    private int getValidSignatureCount(List<String> publicKeyList, List<String> signatures, byte[] data) {
        int validSignatureCount = 0;

        for (int i = 0; i < publicKeyList.size(); i++) {
            String publicKey = publicKeyList.get(i);
            for (int j = 0; j < signatures.size(); j++) {
                String signature = signatures.get(j);
                try {
                    log.log(Level.INFO, "Verifying data: {0} - publicKey: {1} - signature: {2}", new Object[]{Hex.toHexString(data), publicKey, signature});
                    if (Schnorr.verify(Utils.sha256(data), Hex.decode(publicKey), Hex.decode(signature))) {
                        validSignatureCount++;
                        log.log(Level.INFO, "Signature {0} verified (Count: {1})", new Object[]{signature, validSignatureCount});
                    }
                } catch (Exception e) {
                    log.log(Level.WARNING, "Error verifying signature. Continuing...", e);
                }
            }
        }
        return validSignatureCount;
    }
}
