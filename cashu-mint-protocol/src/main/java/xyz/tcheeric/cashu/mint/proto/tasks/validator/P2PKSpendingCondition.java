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
import xyz.tcheeric.cashu.common.model.PublicKey;
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

        // If the number of valid signatures is greater or equal to the number specified in n_sigs, the transaction is valid.
        if (validSignatureCount < n_sigs) {
            throw new CashuErrorException("verify_invalid_number_of_signatures");
        }

        log.log(Level.INFO, "Multisig Verification passed");
    }

    private void verifyLockTime(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {
        int lockTime = proof.getSecret().getLockTime();

        // If the tag locktime is the unix time and the mint's local clock is greater than locktime, the Proof becomes spendable
        if (lockTime > 0 && lockTime < System.currentTimeMillis() / 1000) {
            throw new CashuErrorException("verify_locktime_not_reached");
        }

        log.log(Level.INFO, "Locktime verification passed");
    }

    private void verifyRefundPublicKey(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {

        int lockTime = proof.getSecret().getLockTime();
        if (lockTime > 0 && lockTime > System.currentTimeMillis() / 1000) {

            List<String> refundPublicKeys = proof.getSecret().getRefund();

            // If the locktime is in the past and a tag refund is present,
            // the Proof is spendable only if a valid signature by one of the refund pubkeys is provided in Proof.witness.signatures
            // and, depending on the signature flag, in BlindedMessage.witness.signatures.
            if (refundPublicKeys != null && refundPublicKeys.size() > 0) {

                byte[] data = proof.getSecret().getData();
                List<String> signatures = proof.getWitness().getSignatures();
                String sigFlag = proof.getSecret().getSigFlag();

                if (P2PKSecret.SignatureFlag.SIG_ALL.name().equals(sigFlag)) {

                    if (blindedMessages == null && !blindedMessages.isEmpty()) {
                        throw new IllegalStateException("BlindedMessage list is null or empty");
                    }

                    signatures.addAll(blindedMessages.stream().flatMap(blindedMessage -> blindedMessage.getWitness().getSignatures().stream()).toList());
                }

                for (String pubkey : refundPublicKeys) {
                    if (signatures.stream().anyMatch(signature -> {
                        try {
                            return Schnorr.verify(data, PublicKey.fromString(pubkey).toBytes(), Hex.decode(signature));
                        } catch (Exception e) {
                            log.log(Level.WARNING, "Error verifying signature. No worries. Continuing...", e);
                        }
                        return false;
                    })) {
                        return;
                    }
                }
                throw new CashuErrorException("verify_invalid_refund_signature");
            } else {
                log.log(Level.INFO, "No refund public keys found. Skipping refund verification.");
            }
        } else {
            log.log(Level.INFO, "Locktime not reached for {0}. Skipping refund verification.", proof);
        }

        log.log(Level.INFO, "Refund Verification passed");
    }
}
