package xyz.tcheeric.cashu.mint.proto.service.impl;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-memory vault honours the same contract as the durable one, so tests that run
 * against it exercise the production duplicate semantics (issue #491).
 */
class DefaultSignatureVaultServiceTest {

    private static final KeysetId KEYSET = KeysetId.fromString("009a1f293253e41e");
    private static final String BLINDED_MESSAGE =
            "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";

    private final DefaultSignatureVaultService vault = new DefaultSignatureVaultService();

    /** A second signature on a blinded message already signed is refused as outputs_already_signed. */
    @Test
    void refusesASecondSignatureOnTheSameBlindedMessage() throws CashuErrorException {
        vault.store(message(), signature(8), SignatureSource.MINT);

        assertThatThrownBy(() -> vault.store(message(), signature(8), SignatureSource.SWAP))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);
    }

    /** The refused second store leaves the first signature in place for NUT-09 restore. */
    @Test
    void keepsTheFirstSignatureWhenASecondIsRefused() throws CashuErrorException {
        BlindSignature first = signature(8);
        vault.store(message(), first, SignatureSource.MINT);

        assertThatThrownBy(() -> vault.store(message(), signature(4), SignatureSource.SWAP))
                .isInstanceOf(CashuErrorException.class);

        assertThat(vault.retrieve(message())).isSameAs(first);
    }

    /** The in-memory vault never claims to be durable, which is what the production boot guard checks. */
    @Test
    void reportsItselfAsNotDurable() {
        assertThat(vault.isDurable()).isFalse();
    }

    /** A signature source is mandatory, so no caller can record an issuance without saying why. */
    @Test
    void refusesToStoreWithoutASource() {
        assertThatThrownBy(() -> vault.store(message(), signature(8), null))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.internal_error);
    }

    private static BlindedMessage message() {
        return BlindedMessage.builder()
                .amount(8)
                .keySetId(KEYSET)
                .blindedMessage(PublicKey.fromString(BLINDED_MESSAGE))
                .build();
    }

    private static BlindSignature signature(int amount) {
        return new BlindSignature(amount, KEYSET, SignatureTestData.sampleSignature(), null);
    }
}
