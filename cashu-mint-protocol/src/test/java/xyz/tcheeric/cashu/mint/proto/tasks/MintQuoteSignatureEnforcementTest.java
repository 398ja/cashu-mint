package xyz.tcheeric.cashu.mint.proto.tasks;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignature;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignatureMessage;
import xyz.tcheeric.cashu.crypto.Schnorr;

import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NUT-20: who may mint a quote that was locked to a key.
 *
 * <p>These exercise the decision {@code MintTask} makes before it signs anything. The property
 * that matters is not that a correct signature works, but that everything else is refused: an
 * unlocked quote is a bearer token, so a quote id in a log line is enough to take its ecash.
 */
class MintQuoteSignatureEnforcementTest {

    private static final String QUOTE = "9d745270-1405-46de-b5c5-e2762b4f5e00";
    private static final byte[] PRIVATE_KEY =
            Hex.decode("0000000000000000000000000000000000000000000000000000000000000003");
    private static final String PUBLIC_KEY =
            "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";

    private static BlindedMessage output(int amount) {
        BlindedMessage message = new BlindedMessage();
        message.setAmount(amount);
        message.setKeySetId(KeysetId.fromString("009a1f293253e41e"));
        message.setBlindedMessage(PublicKey.fromString(
                "035015e6d7ade60ba8426cefaf1832bbd27257636e44a76b922d78e79b47cb689d"));
        return message;
    }

    private static String sign(String quoteId, List<BlindedMessage> outputs) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(MintQuoteSignatureMessage.forQuote(quoteId, outputs));
        return Hex.toHexString(Schnorr.sign(hash, PRIVATE_KEY));
    }

    /**
     * Ensures the wallet that locked the quote can mint it, so locking does not cost the honest
     * holder anything.
     */
    @Test
    void shouldLetTheLockingWalletMintItsOwnQuote() throws Exception {
        // Arrange
        List<BlindedMessage> outputs = List.of(output(8));
        String signature = sign(QUOTE, outputs);

        // Act
        boolean valid = MintQuoteSignature.isValid(QUOTE, outputs, PUBLIC_KEY, signature);

        // Assert
        assertThat(valid).isTrue();
    }

    /**
     * Ensures someone who has only learned the quote id cannot mint it, which is the attack
     * NUT-20 exists to stop: quote ids travel through logs, webhooks and trace events.
     */
    @Test
    void shouldRefuseAnAttackerWhoKnowsOnlyTheQuoteId() throws Exception {
        // Arrange: the attacker has the quote id and their own key, but not the quote's key.
        byte[] attackerKey =
                Hex.decode("0000000000000000000000000000000000000000000000000000000000000007");
        List<BlindedMessage> outputs = List.of(output(8));
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(MintQuoteSignatureMessage.forQuote(QUOTE, outputs));
        String attackerSignature = Hex.toHexString(Schnorr.sign(hash, attackerKey));

        // Act
        boolean valid = MintQuoteSignature.isValid(QUOTE, outputs, PUBLIC_KEY, attackerSignature);

        // Assert
        assertThat(valid).isFalse();
    }

    /**
     * Ensures a signature captured from a legitimate mint request cannot be replayed with the
     * attacker's own outputs, which would redirect the ecash while keeping a valid-looking
     * signature.
     */
    @Test
    void shouldRefuseACapturedSignatureReplayedWithDifferentOutputs() throws Exception {
        // Arrange
        List<BlindedMessage> honestOutputs = List.of(output(8));
        String captured = sign(QUOTE, honestOutputs);
        BlindedMessage attackerOutput = output(8);
        attackerOutput.setBlindedMessage(PublicKey.fromString(
                "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"));

        // Act
        boolean valid =
                MintQuoteSignature.isValid(QUOTE, List.of(attackerOutput), PUBLIC_KEY, captured);

        // Assert
        assertThat(valid).isFalse();
    }
}
