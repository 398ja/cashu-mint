package xyz.tcheeric.cashu.mint.proto.util;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignatureMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;

import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NUT-20 rules shared by regular and voucher mint quotes: which keys a quote may be locked to, and
 * what a mint request must carry to unlock it.
 */
class MintQuoteLockTest {

    private static final String QUOTE = "9d745270-1405-46de-b5c5-e2762b4f5e00";
    private static final byte[] PRIVATE_KEY =
            Hex.decode("0000000000000000000000000000000000000000000000000000000000000003");
    private static final byte[] OTHER_PRIVATE_KEY =
            Hex.decode("0000000000000000000000000000000000000000000000000000000000000007");
    private static final String PUBLIC_KEY =
            "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";

    private static List<BlindedMessage> outputs() {
        BlindedMessage message = new BlindedMessage();
        message.setAmount(8);
        message.setKeySetId(KeysetId.fromString("009a1f293253e41e"));
        message.setBlindedMessage(PublicKey.fromString(
                "035015e6d7ade60ba8426cefaf1832bbd27257636e44a76b922d78e79b47cb689d"));
        return List.of(message);
    }

    private static String sign(byte[] privateKey, List<BlindedMessage> outputs) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(MintQuoteSignatureMessage.forQuote(QUOTE, outputs));
        return Hex.toHexString(Schnorr.sign(hash, privateKey));
    }

    private static CashuErrorCode codeOf(Throwable thrown) {
        return ((CashuErrorException) thrown).getErrorCode();
    }

    /** Ensures a quote request without a key yields an unlocked quote, as NUT-04 has it. */
    @Test
    void shouldLeaveTheQuoteUnlockedWhenNoKeyIsRequested() throws Exception {
        // Act
        String lockingKey = MintQuoteLock.lockingKey(null);

        // Assert
        assertThat(lockingKey).isNull();
    }

    /**
     * Ensures an explicitly sent empty or blank key is refused with 20009 rather than read as
     * "unlocked": the wallet asked to lock, and quietly handing it a bearer quote would be worse.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void shouldRefuseAnExplicitlyBlankKey(String requested) {
        // Act and Assert
        assertThatThrownBy(() -> MintQuoteLock.lockingKey(requested))
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.pubkey_required_for_mint_quote));
    }

    /** Ensures an uppercase key is stored and echoed in canonical lowercase hex. */
    @Test
    void shouldNormaliseTheKeyToLowercase() throws Exception {
        // Act
        String lockingKey = MintQuoteLock.lockingKey(PUBLIC_KEY.toUpperCase());

        // Assert
        assertThat(lockingKey).isEqualTo(PUBLIC_KEY);
    }

    /**
     * Ensures a lock is refused with 20009 when the mint has nowhere durable to store it, so a
     * wallet is never told its quote is locked while anyone holding the id could mint it.
     */
    @Test
    void shouldRefuseALockThatCannotBeStored() {
        // Act and Assert
        assertThatThrownBy(() -> MintQuoteLock.requireStorable(PUBLIC_KEY, false))
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.pubkey_required_for_mint_quote));
    }

    /** Ensures an unlocked quote needs no storage, so mints without JPA keep issuing quotes. */
    @Test
    void shouldAllowAnUnlockedQuoteWithoutStorage() {
        // Act and Assert
        assertThatCode(() -> MintQuoteLock.requireStorable(null, false)).doesNotThrowAnyException();
    }

    /** Ensures a lock is accepted when the durable store is wired. */
    @Test
    void shouldAllowALockThatCanBeStored() {
        // Act and Assert
        assertThatCode(() -> MintQuoteLock.requireStorable(PUBLIC_KEY, true)).doesNotThrowAnyException();
    }

    /** Ensures a valid compressed key is accepted and kept exactly as the wallet sent it. */
    @Test
    void shouldAcceptACompressedSecp256k1Key() throws Exception {
        // Act
        String lockingKey = MintQuoteLock.lockingKey(PUBLIC_KEY);

        // Assert
        assertThat(lockingKey).isEqualTo(PUBLIC_KEY);
    }

    /**
     * Ensures a key the mint could never verify a signature against is refused with 20009 when
     * the quote is requested, rather than creating a quote nobody can ever mint.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "zz",
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            "020000000000000000000000000000000000000000000000000000000000000005",
            "04f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            "0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
                    + "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8"})
    void shouldRefuseAKeyThatIsNotACompressedPoint(String requested) {
        // Act and Assert
        assertThatThrownBy(() -> MintQuoteLock.lockingKey(requested))
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.pubkey_required_for_mint_quote));
    }

    /** Ensures an unlocked quote needs no signature, so existing wallets keep working. */
    @Test
    void shouldLetAnUnlockedQuoteMintWithoutASignature() {
        // Act and Assert
        assertThatCode(() -> MintQuoteLock.requireUnlockedBy(null, QUOTE, outputs(), null))
                .doesNotThrowAnyException();
    }

    /** Ensures a locked quote refuses a request with no signature, with 20008 as NUT-20 requires. */
    @Test
    void shouldRefuseALockedQuoteWithoutASignature() {
        // Act and Assert
        assertThatThrownBy(() -> MintQuoteLock.requireUnlockedBy(PUBLIC_KEY, QUOTE, outputs(), null))
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.mint_signature_invalid));
    }

    /** Ensures a signature from any key other than the locking key is refused with 20008. */
    @Test
    void shouldRefuseALockedQuoteSignedByAnotherKey() throws Exception {
        // Arrange
        List<BlindedMessage> outputs = outputs();
        String signature = sign(OTHER_PRIVATE_KEY, outputs);

        // Act and Assert
        assertThatThrownBy(() -> MintQuoteLock.requireUnlockedBy(PUBLIC_KEY, QUOTE, outputs, signature))
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.mint_signature_invalid));
    }

    /** Ensures the holder of the locking key can mint the quote. */
    @Test
    void shouldLetTheLockingKeyMintItsQuote() throws Exception {
        // Arrange
        List<BlindedMessage> outputs = outputs();
        String signature = sign(PRIVATE_KEY, outputs);

        // Act and Assert
        assertThatCode(() -> MintQuoteLock.requireUnlockedBy(PUBLIC_KEY, QUOTE, outputs, signature))
                .doesNotThrowAnyException();
    }
}
