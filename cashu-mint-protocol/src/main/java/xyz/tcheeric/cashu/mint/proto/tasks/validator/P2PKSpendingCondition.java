package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;

/**
 * NUT-11 Pay-to-Public-Key enforcement.
 *
 * <p>Two things vary between the signature flags, and only two: <b>what</b> is signed and
 * <b>where</b> the signatures live.
 * <ul>
 *   <li>{@code SIG_INPUTS} — each input is signed on its own secret, in its own witness.</li>
 *   <li>{@code SIG_ALL} — one aggregated message over every input and output, carried in the
 *       first input's witness only.</li>
 * </ul>
 * Everything downstream of that choice (the primary n-of-m multisig, the locktime, the refund
 * pathway) is identical, so it is expressed once against a {@link SignedTransactionMessage}.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/11.md">NUT-11</a>
 */
@Slf4j
public class P2PKSpendingCondition implements SpendingCondition<P2PKSecret> {

    private final P2PKTransaction transaction;

    /**
     * The full transaction form, required for {@code SIG_ALL}: the aggregated message cannot be
     * built from a single proof.
     */
    public P2PKSpendingCondition(@NonNull P2PKTransaction transaction) {
        this.transaction = transaction;
    }

    /**
     * The outputs-only form, for callers evaluating a proof outside a known transaction. It can
     * only decide {@code SIG_INPUTS}; a {@code SIG_ALL} proof reaching this constructor is
     * rejected, because there is no transaction to bind its signature to.
     */
    public P2PKSpendingCondition(List<BlindedMessage> blindedMessages) {
        this(new P2PKTransaction(Collections.emptyList(), blindedMessages, null));
    }

    @Override
    public void verify(@NonNull Proof<P2PKSecret> proof) throws CashuErrorException {
        log.info("Verifying P2PK spending condition for {}", proof);
        SignedTransactionMessage signed = resolveSignedMessage(proof);

        // NUT-11: the primary n-of-m multisig (data + pubkeys tag) is a valid spend path at ANY
        // time, before or after the locktime. This is the escrow's normal 2-of-3 release.
        if (signed.satisfiesPrimaryPath()) {
            log.info("Multisig Verification passed");
            return;
        }

        // The primary path did not meet n_sigs. Before the locktime there is no other path.
        if (!signed.locktimeHasPassed()) {
            log.error("verify_invalid_number_of_signatures");
            throw new CashuErrorException(CashuErrorCode.verify_invalid_number_of_signatures);
        }

        // After the locktime the refund keys may reclaim; absent refund keys the proof is unlocked.
        List<String> refundKeys = signed.secret().getRefund();
        if (refundKeys == null || refundKeys.isEmpty()) {
            log.info("Locktime passed and no refund keys present — proof is unlocked");
            return;
        }
        if (!signed.satisfiesRefundPath(refundKeys)) {
            log.error("verify_invalid_refund_signature");
            throw new CashuErrorException(CashuErrorCode.verify_invalid_refund_signature);
        }
        log.info("Refund Verification passed");
    }

    /**
     * Decides what message this proof's signatures must cover and which witness carries them.
     *
     * <p>Under {@code SIG_ALL} the answer is a property of the transaction, not of the proof: the
     * uniformity precondition is enforced first, then the aggregated message is checked against
     * the first input's witness. Under {@code SIG_INPUTS} the proof answers for itself.
     */
    private SignedTransactionMessage resolveSignedMessage(Proof<P2PKSecret> proof) throws CashuErrorException {
        if (!SignatureFlags.isSigAll(proof.getSecret().getSigFlag())) {
            return new SignedTransactionMessage(proof.getSecret(), proof.getWitness(),
                    proof.getSecret().toString().getBytes(StandardCharsets.UTF_8));
        }
        if (transaction.inputs().isEmpty()) {
            log.error("SIG_ALL proof verified outside a transaction — no aggregated message to check");
            throw new CashuErrorException(CashuErrorCode.sigall_missing_inputs);
        }
        transaction.requireUniformSpendingCondition();
        Proof<? extends Secret> firstInput = transaction.witnessBearingInput();
        return new SignedTransactionMessage((P2PKSecret) firstInput.getSecret(), firstInput.getWitness(),
                transaction.sigAllMessage().toBytes());
    }

    /**
     * A NUT-11 spending condition paired with the message its signatures must cover and the
     * witness that carries them.
     */
    private record SignedTransactionMessage(P2PKSecret secret, Witness witness, byte[] message) {

        /** The primary pathway: the {@code data} key plus the {@code pubkeys} tag, meeting {@code n_sigs}. */
        boolean satisfiesPrimaryPath() {
            return signingKeyCount(primaryPublicKeys()) >= threshold(secret.getNSigs());
        }

        /** The refund pathway: the {@code refund} tag keys, meeting {@code n_sigs_refund}. */
        boolean satisfiesRefundPath(List<String> refundKeys) {
            return signingKeyCount(refundKeys) >= threshold(secret.getNSigsRefund());
        }

        boolean locktimeHasPassed() {
            int locktime = secret.getLockTime();
            return locktime > 0 && locktime < System.currentTimeMillis() / 1000;
        }

        private int signingKeyCount(List<String> publicKeys) {
            List<String> signatures = witness == null ? null : witness.getSignatures();
            return SigningKeyCounter.countSigningKeys(publicKeys, signatures, message);
        }

        /** NUT-11 defaults an absent or unset signature threshold to one. */
        private static int threshold(int declared) {
            return declared > 0 ? declared : 1;
        }

        private List<String> primaryPublicKeys() {
            List<String> tag = secret.getPubKeys();
            List<String> keys = new ArrayList<>(1 + (tag == null ? 0 : tag.size()));
            keys.add(Hex.toHexString(secret.getData()));
            if (tag != null) {
                keys.addAll(tag);
            }
            return keys;
        }
    }
}
