package xyz.tcheeric.cashu.mint.proto.tasks;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignature;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignatureMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * Ensures a locked regular quote minted with no signature is refused through MintTask with
     * 20008, as NUT-20 requires ("no valid signature"), and is left PAID for its key holder. It
     * used to answer 20009, which NUT-20 reserves for a missing or invalid pubkey.
     */
    @Test
    void shouldRefuseALockedQuoteWithNoSignatureWith20008() {
        // Arrange
        MintQuoteRepository quotes = Mockito.mock(MintQuoteRepository.class);
        when(quotes.findById(QUOTE)).thenReturn(Optional.of(new LockedQuote(QUOTE, 8L, PUBLIC_KEY)));
        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(QUOTE);
        request.setBlindedMessages(List.of(output(8)));

        // Act and Assert
        try (MockedConstruction<SignBlindedMessageTask> signer = signerThatAlwaysSigns()) {
            assertThatThrownBy(() -> regularMint(request, quotes).execute())
                    .isInstanceOf(CashuErrorException.class)
                    .satisfies(thrown -> assertThat(((CashuErrorException) thrown).getErrorCode())
                            .isEqualTo(CashuErrorCode.mint_signature_invalid));
            assertThat(signer.constructed()).isEmpty();
        }
        verify(quotes, never()).casLifecycle(anyString(), any(), any());
    }

    private static MintTask<Secret> regularMint(PostMintRequest<Secret> request, MintQuoteRepository quotes) {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.checkPaymentStatus(anyString())).thenReturn(true);
        when(gateway.getAmount(anyString())).thenReturn(8);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(gateway);
        return new MintTask<>(request, PaymentMethod.BOLT11, null, mintWithKeyset(), service,
                new DefaultSignatureVaultService(), null, quotes, Mockito.mock(IssuanceRecordRepository.class));
    }

    private static MockedConstruction<SignBlindedMessageTask> signerThatAlwaysSigns() {
        return Mockito.mockConstruction(SignBlindedMessageTask.class,
                (task, context) -> when(task.execute()).thenReturn(new BlindSignature(
                        8, KeysetId.fromString("009a1f293253e41e"), SignatureTestData.sampleSignature(), null)));
    }

    private static Mint mintWithKeyset() {
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString(
                String.format("%064x", 8))));
        Mint mint = new Mint();
        mint.addKeySet(KeySet.builder().id("009a1f293253e41e").unit("sat").keys(keys).build());
        return mint;
    }

    private record LockedQuote(String quoteId, long amount, String pubkey) implements MintQuote {
        @Override public String unit() { return "sat"; }
        @Override public String mintUrl() { return "https://mint.example"; }
        @Override public String paymentMethod() { return "bolt11"; }
        @Override public String invoiceId() { return quoteId; }
        @Override public LifecycleState lifecycleState() { return LifecycleState.PAID; }
        @Override public String requestHash() { return "0".repeat(64); }
        @Override public Instant createdAt() { return null; }
        @Override public Instant updatedAt() { return null; }
    }
}
