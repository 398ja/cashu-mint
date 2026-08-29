package xyz.tcheeric.cashu.mint.proto.spending;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKTransaction;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SigAllMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code SIG_ALL} actually binds: the whole transaction, and for a melt, the quote being paid.
 *
 * <p>These proofs are signed here rather than copied from the spec's vector file. That is
 * deliberate and it is a limitation worth naming: the published vectors cannot verify against this
 * stack yet, because our NUT-10 secret serialization diverges from the spec (cashu-lib#254), and
 * {@link Nut11TestVectorsTest} is disabled pending that fix. Signing locally sidesteps the
 * serialization question entirely — every message here is built and verified with the same
 * encoding — so these tests speak to the <em>semantics</em> of SIG_ALL, which is what issue #383
 * changed, and say nothing about wire interoperability, which #254 owns.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/11.md">NUT-11</a>
 */
class SigAllTransactionBindingTest {

    private static final String QUOTE = "quote-being-paid";
    private static final String OTHER_QUOTE = "a-different-quote";

    // ----- fixtures -----

    private static PrivateKey key() {
        return PrivateKey.fromBytes(Schnorr.generatePrivateKey());
    }

    private static byte[] pub(PrivateKey priv) {
        return PublicKey.derivePublicKey(priv).getBytes();
    }

    /** A SIG_ALL secret locked to one key, with no extra tags. */
    private static P2PKSecret sigAllSecret(PrivateKey owner) {
        P2PKSecret secret = new P2PKSecret(pub(owner));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_ALL);
        return secret;
    }

    /**
     * An input carrying a real unblinded signature {@code C}. {@code C} is part of the aggregated
     * message, so it must be present and stable for these tests to mean anything.
     */
    private static Proof<P2PKSecret> input(P2PKSecret secret) {
        P2PKProof proof = new P2PKProof();
        proof.setAmount(2);
        proof.setSecret(secret);
        proof.setUnblindedSignature(
                Signature.fromString(PublicKey.derivePublicKey(key()).toString()));
        return proof;
    }

    private static BlindedMessage output(int amount) {
        BlindedMessage blindedMessage = new BlindedMessage();
        blindedMessage.setAmount(amount);
        blindedMessage.setBlindedMessage(PublicKey.derivePublicKey(key()));
        return blindedMessage;
    }

    /** Signs the transaction's aggregated message and parks the signature on the first input. */
    private static void signSigAll(P2PKTransaction transaction, PrivateKey... signers)
            throws Exception {
        byte[] hash = Utils.sha256(transaction.sigAllMessage().toBytes());
        Witness witness = new Witness();
        for (PrivateKey signer : signers) {
            witness.addSignature(Hex.toHexString(Schnorr.sign(hash, signer.toBytes())));
        }
        transaction.witnessBearingInput().setWitness(witness);
    }

    private static void assertSpendable(P2PKTransaction transaction) throws Exception {
        Proof<?> first = transaction.witnessBearingInput();
        assertDoesNotThrow(() -> new P2PKSpendingCondition(transaction)
                .verify(asP2PK(first)));
    }

    private static void assertUnspendable(P2PKTransaction transaction) throws Exception {
        Proof<?> first = transaction.witnessBearingInput();
        assertThrows(CashuErrorException.class, () -> new P2PKSpendingCondition(transaction)
                .verify(asP2PK(first)));
    }

    @SuppressWarnings("unchecked")
    private static Proof<P2PKSecret> asP2PK(Proof<?> proof) {
        return (Proof<P2PKSecret>) proof;
    }

    // ===== swap: the aggregate covers every output, in order =====

    /** A swap signed over its own aggregate verifies. This is the baseline the rest vary from. */
    @Test
    void swapSignedOverItsOwnAggregate_isSpendable() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(in), List.of(output(1), output(1)));
        signSigAll(transaction, owner);

        assertSpendable(transaction);
    }

    /**
     * REORDERING AN OUTPUT INVALIDATES THE SIGNATURE.
     *
     * <p>The witness is made over {@code [first, second]}. Presenting the same two outputs as
     * {@code [second, first]} — same amounts, same B_ values, same total — must fail. Under the
     * previous per-output scheme both orderings verified, because each output was checked against
     * its own B_ in isolation and order was never committed to.
     */
    @Test
    void reorderingAnOutput_invalidatesTheSignature() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        BlindedMessage first = output(1);
        BlindedMessage second = output(1);

        P2PKTransaction asSigned = P2PKTransaction.forSwap(List.of(in), List.of(first, second));
        signSigAll(asSigned, owner);
        assertSpendable(asSigned);

        P2PKTransaction reordered = P2PKTransaction.forSwap(List.of(in), List.of(second, first));
        assertUnspendable(reordered);
    }

    /** Reordering genuinely changes the message, not merely the verification outcome. */
    @Test
    void reorderingAnOutput_changesTheAggregatedMessage() {
        Proof<P2PKSecret> in = input(sigAllSecret(key()));
        BlindedMessage first = output(1);
        BlindedMessage second = output(2);

        assertNotEquals(
                SigAllMessage.forSwap(List.of(in), List.of(first, second)).value(),
                SigAllMessage.forSwap(List.of(in), List.of(second, first)).value());
    }

    /** Substituting an output for one the signer never saw invalidates the signature. */
    @Test
    void substitutingAnOutput_invalidatesTheSignature() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));

        P2PKTransaction asSigned = P2PKTransaction.forSwap(List.of(in), List.of(output(2)));
        signSigAll(asSigned, owner);
        assertSpendable(asSigned);

        assertUnspendable(P2PKTransaction.forSwap(List.of(in), List.of(output(2))));
    }

    /**
     * Changing only an output's amount invalidates the signature. The previous implementation
     * signed B_ alone, so an output could be inflated without breaking any signature.
     */
    @Test
    void changingAnOutputAmount_invalidatesTheSignature() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        BlindedMessage out = output(2);

        P2PKTransaction asSigned = P2PKTransaction.forSwap(List.of(in), List.of(out));
        signSigAll(asSigned, owner);
        assertSpendable(asSigned);

        out.setAmount(64);
        assertUnspendable(P2PKTransaction.forSwap(List.of(in), List.of(out)));
    }

    /** Appending an extra output invalidates the signature. */
    @Test
    void appendingAnOutput_invalidatesTheSignature() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        BlindedMessage out = output(2);

        P2PKTransaction asSigned = P2PKTransaction.forSwap(List.of(in), List.of(out));
        signSigAll(asSigned, owner);
        assertSpendable(asSigned);

        assertUnspendable(P2PKTransaction.forSwap(List.of(in), List.of(out, output(1))));
    }

    /** Dropping an output invalidates the signature. */
    @Test
    void droppingAnOutput_invalidatesTheSignature() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        BlindedMessage kept = output(1);

        P2PKTransaction asSigned = P2PKTransaction.forSwap(List.of(in), List.of(kept, output(1)));
        signSigAll(asSigned, owner);
        assertSpendable(asSigned);

        assertUnspendable(P2PKTransaction.forSwap(List.of(in), List.of(kept)));
    }

    // ===== melt: the aggregate binds the quote =====

    /** A melt signed over its own quote verifies. */
    @Test
    void meltSignedOverItsOwnQuote_isSpendable() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        P2PKTransaction transaction =
                P2PKTransaction.forMelt(List.of(in), QUOTE, List.of(output(0)));
        signSigAll(transaction, owner);

        assertSpendable(transaction);
    }

    /**
     * A WITNESS FROM ONE MELT QUOTE FAILS AGAINST ANOTHER. This is the replay gap #383 exists to
     * close, and it is asserted directly rather than inferred from the code path.
     *
     * <p>The scenario: an observer sees a melt for {@code QUOTE} — its inputs and its witness are
     * both public in the request. They resubmit exactly those bytes naming {@code OTHER_QUOTE}.
     * Before the quote id entered the aggregated message nothing distinguished the two requests,
     * so the second was paid on the strength of the first one's signature.
     */
    @Test
    void witnessFromOneMeltQuote_failsAgainstAnother() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        List<BlindedMessage> blankOutputs = List.of(output(0));

        P2PKTransaction paidQuote = P2PKTransaction.forMelt(List.of(in), QUOTE, blankOutputs);
        signSigAll(paidQuote, owner);
        assertSpendable(paidQuote);

        // Same inputs, same witness, different quote. Must not verify.
        P2PKTransaction replayed =
                P2PKTransaction.forMelt(List.of(in), OTHER_QUOTE, blankOutputs);
        assertUnspendable(replayed);
    }

    /** The quote id is genuinely in the message, not merely consulted during verification. */
    @Test
    void theQuoteIdIsPartOfTheAggregatedMessage() {
        Proof<P2PKSecret> in = input(sigAllSecret(key()));
        List<BlindedMessage> blankOutputs = List.of(output(0));

        String paid = SigAllMessage.forMelt(List.of(in), QUOTE, blankOutputs).value();
        String other = SigAllMessage.forMelt(List.of(in), OTHER_QUOTE, blankOutputs).value();

        assertNotEquals(paid, other);
        assertTrue(paid.endsWith(QUOTE), "NUT-11 puts the quote id last in the melt message");
    }

    /**
     * A melt witness is not replayable as a swap either: the swap message omits the quote id, so
     * the two aggregations differ even over identical inputs and outputs.
     */
    @Test
    void meltWitness_failsAsASwap() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        List<BlindedMessage> outputs = List.of(output(0));

        P2PKTransaction melt = P2PKTransaction.forMelt(List.of(in), QUOTE, outputs);
        signSigAll(melt, owner);
        assertSpendable(melt);

        assertUnspendable(P2PKTransaction.forSwap(List.of(in), outputs));
    }

    /** Conversely a swap witness cannot be presented as a melt for any quote. */
    @Test
    void swapWitness_failsAsAMelt() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> in = input(sigAllSecret(owner));
        List<BlindedMessage> outputs = List.of(output(2));

        P2PKTransaction swap = P2PKTransaction.forSwap(List.of(in), outputs);
        signSigAll(swap, owner);
        assertSpendable(swap);

        assertUnspendable(P2PKTransaction.forMelt(List.of(in), QUOTE, outputs));
    }

    // ===== the uniformity precondition =====

    /** Two inputs sharing one spending condition are accepted, and both are covered by one witness. */
    @Test
    void uniformInputs_areAccepted() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> first = input(sigAllSecret(owner));
        Proof<P2PKSecret> second = input(sigAllSecret(owner));

        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(first, second), List.of(output(4)));
        signSigAll(transaction, owner);

        assertSpendable(transaction);
    }

    /** Inputs locked to different keys do not share a spending condition. */
    @Test
    void inputsWithDifferentData_areRejected() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> first = input(sigAllSecret(owner));
        Proof<P2PKSecret> second = input(sigAllSecret(key())); // a different key

        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(first, second), List.of(output(4)));
        signSigAll(transaction, owner);

        assertUnspendable(transaction);
    }

    /** Inputs differing in their tags do not share a spending condition. */
    @Test
    void inputsWithDifferentTags_areRejected() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> first = input(sigAllSecret(owner));

        P2PKSecret tagged = sigAllSecret(owner);
        tagged.setLockTime(Integer.MAX_VALUE); // an extra tag the first input does not carry
        Proof<P2PKSecret> second = input(tagged);

        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(first, second), List.of(output(4)));
        signSigAll(transaction, owner);

        assertUnspendable(transaction);
    }

    /**
     * MIXING SIG_ALL AND SIG_INPUTS IS REJECTED. One SIG_ALL input switches the whole transaction
     * over, and a SIG_INPUTS input cannot then satisfy the shared condition.
     */
    @Test
    void mixingSigAllWithSigInputs_isRejected() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> sigAll = input(sigAllSecret(owner));

        P2PKSecret sigInputsSecret = new P2PKSecret(pub(owner));
        sigInputsSecret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        Proof<P2PKSecret> sigInputs = input(sigInputsSecret);

        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(sigAll, sigInputs), List.of(output(4)));
        signSigAll(transaction, owner);

        assertUnspendable(transaction);
    }

    /** A transaction is SIG_ALL if any input says so, whichever position that input holds. */
    @Test
    void anySigAllInputMakesTheTransactionSigAll() throws Exception {
        P2PKSecret sigInputsSecret = new P2PKSecret(pub(key()));
        sigInputsSecret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        Proof<P2PKSecret> sigInputs = input(sigInputsSecret);
        Proof<P2PKSecret> sigAll = input(sigAllSecret(key()));

        assertTrue(P2PKTransaction.forSwap(List.of(sigInputs, sigAll), List.of()).isSigAll());
        assertTrue(P2PKTransaction.forSwap(List.of(sigAll, sigInputs), List.of()).isSigAll());
        assertEquals(false,
                P2PKTransaction.forSwap(List.of(sigInputs), List.of()).isSigAll());
    }

    // ===== the witness lives on the first input =====

    /**
     * ONLY THE FIRST INPUT'S WITNESS IS CONSULTED. The signature is placed on the *second* input
     * and the first is left bare. NUT-11 reads only the first input's witness, so this must fail
     * even though a perfectly valid signature over the correct message is present in the request.
     */
    @Test
    void aWitnessOnALaterInputIsIgnored() throws Exception {
        PrivateKey owner = key();
        Proof<P2PKSecret> first = input(sigAllSecret(owner));
        Proof<P2PKSecret> second = input(sigAllSecret(owner));
        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(first, second), List.of(output(4)));

        // Sign the correct message, but park it on the second input.
        byte[] hash = Utils.sha256(transaction.sigAllMessage().toBytes());
        Witness misplaced = new Witness();
        misplaced.addSignature(Hex.toHexString(Schnorr.sign(hash, owner.toBytes())));
        second.setWitness(misplaced);
        first.setWitness(null);

        assertUnspendable(transaction);

        // Moving that same witness to the first input makes the very same transaction spendable,
        // which isolates witness *location* as the only difference.
        second.setWitness(null);
        first.setWitness(misplaced);
        assertSpendable(transaction);
    }

    /** The first input's witness is what is read even when later inputs carry decoy signatures. */
    @Test
    void decoySignaturesOnLaterInputsDoNotHelp() throws Exception {
        PrivateKey owner = key();
        PrivateKey attacker = key();
        Proof<P2PKSecret> first = input(sigAllSecret(owner));
        Proof<P2PKSecret> second = input(sigAllSecret(owner));
        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(first, second), List.of(output(4)));

        byte[] hash = Utils.sha256(transaction.sigAllMessage().toBytes());
        Witness attackerWitness = new Witness();
        attackerWitness.addSignature(Hex.toHexString(Schnorr.sign(hash, attacker.toBytes())));
        first.setWitness(attackerWitness);

        Witness ownerWitness = new Witness();
        ownerWitness.addSignature(Hex.toHexString(Schnorr.sign(hash, owner.toBytes())));
        second.setWitness(ownerWitness);

        assertUnspendable(transaction);
    }

    // ===== thresholds still count distinct keys under SIG_ALL =====

    /** A 2-of-3 SIG_ALL lock releases to two distinct signers over the aggregate. */
    @Test
    void sigAllMultisig_twoDistinctSigners_isSpendable() throws Exception {
        PrivateKey a = key();
        PrivateKey b = key();
        PrivateKey c = key();

        P2PKSecret secret = new P2PKSecret(pub(a));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_ALL);
        secret.setNSigs(2);
        secret.setPubKeys(List.of(Hex.toHexString(pub(b)), Hex.toHexString(pub(c))));

        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(input(secret)), List.of(output(2)));
        signSigAll(transaction, a, b);

        assertSpendable(transaction);
    }

    /**
     * One key signing twice does not meet a 2-of-3 threshold, under SIG_ALL as under SIG_INPUTS.
     * Schnorr is non-deterministic, so two distinct signature bytes from one key would otherwise
     * look like two signers.
     */
    @Test
    void sigAllMultisig_oneKeySigningTwice_isRejected() throws Exception {
        PrivateKey a = key();
        PrivateKey b = key();
        PrivateKey c = key();

        P2PKSecret secret = new P2PKSecret(pub(a));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_ALL);
        secret.setNSigs(2);
        secret.setPubKeys(List.of(Hex.toHexString(pub(b)), Hex.toHexString(pub(c))));

        P2PKTransaction transaction =
                P2PKTransaction.forSwap(List.of(input(secret)), List.of(output(2)));
        signSigAll(transaction, a, a); // the same key twice, two distinct signatures

        assertUnspendable(transaction);
    }

    // ===== SIG_INPUTS is untouched =====

    /**
     * A SIG_INPUTS proof is verified against its own secret whatever the transaction carries.
     * This is the compatibility guarantee for deployed escrow proofs, which are all SIG_INPUTS:
     * changing SIG_ALL must not move them.
     */
    @Test
    void sigInputsProof_isUnaffectedByTheTransactionShape() throws Exception {
        PrivateKey owner = key();
        P2PKSecret secret = new P2PKSecret(pub(owner));
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);

        Proof<P2PKSecret> in = input(secret);
        Witness witness = new Witness();
        witness.addSignature(Hex.toHexString(
                Schnorr.sign(Utils.sha256(secret.toString().getBytes()), owner.toBytes())));
        in.setWitness(witness);

        List<P2PKTransaction> shapes = new ArrayList<>();
        shapes.add(P2PKTransaction.forSwap(List.of(in), List.of()));
        shapes.add(P2PKTransaction.forSwap(List.of(in), List.of(output(1), output(2))));
        shapes.add(P2PKTransaction.forMelt(List.of(in), QUOTE, List.of(output(0))));
        shapes.add(P2PKTransaction.forMelt(List.of(in), OTHER_QUOTE, List.of(output(0))));

        for (P2PKTransaction shape : shapes) {
            assertDoesNotThrow(() -> new P2PKSpendingCondition(shape).verify(in),
                    "SIG_INPUTS must not depend on the transaction shape");
        }
    }

    /** A SIG_ALL proof evaluated outside any transaction is rejected, not downgraded. */
    @Test
    void sigAllOutsideATransaction_isRejected() {
        Proof<P2PKSecret> in = input(sigAllSecret(key()));
        assertThrows(CashuErrorException.class,
                () -> new P2PKSpendingCondition(List.<BlindedMessage>of()).verify(in));
    }
}
