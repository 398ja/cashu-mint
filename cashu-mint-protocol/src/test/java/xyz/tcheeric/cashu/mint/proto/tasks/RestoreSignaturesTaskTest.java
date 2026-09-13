package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RestoreSignaturesTaskTest {

    /**
     * Tests that previously stored signatures are correctly returned during a restore operation.
     * Verifies the basic restore flow: store a signature, then retrieve it via RestoreSignaturesTask.
     */
    @Test
    void restoresStoredSignatures() throws CashuErrorException {
        DefaultSignatureVaultService service = new DefaultSignatureVaultService();
        BlindedMessage message = Mockito.mock(BlindedMessage.class);
        PublicKey pk = Mockito.mock(PublicKey.class);
        Mockito.when(message.getBlindedMessage()).thenReturn(pk);
        Mockito.when(pk.toString()).thenReturn("pk1");

        BlindSignature signature = Mockito.mock(BlindSignature.class);
        KeysetId keysetId = KeysetId.fromString("009a1f293253e41e");
        Mockito.when(signature.getKeySetId()).thenReturn(keysetId);
        Mockito.when(signature.getAmount()).thenReturn(8);

        service.store(message, signature);

        PostRestoreRequest request = new PostRestoreRequest(List.of(message));
        RestoreSignaturesTask task = new RestoreSignaturesTask(request, service);
        var response = task.execute();

        PostRestoreResponse expected =
                new PostRestoreResponse(List.of(message), List.of(signature));
        assertThat(response).isEqualTo(expected);
    }

    /**
     * Confirms that a restore request carrying more outputs than the declared maximum is refused
     * with too_many_outputs, and that the signature vault is never consulted. The task performs
     * one vault lookup per output and the endpoint is unauthenticated, so an unbounded list is an
     * amplification attack on the vault every value-moving path depends on.
     */
    @Test
    void refusesMoreOutputsThanTheMaximumWithoutTouchingTheVault() {
        SignatureVaultService vault = Mockito.mock(SignatureVaultService.class);
        BlindedMessage message = Mockito.mock(BlindedMessage.class);
        PostRestoreRequest request = new PostRestoreRequest(
                Collections.nCopies(PostRestoreRequest.MAX_OUTPUTS + 1, message));

        RestoreSignaturesTask task = new RestoreSignaturesTask(request, vault);

        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .extracting(thrown -> ((CashuErrorException) thrown).getErrorCode())
                .isEqualTo(CashuErrorCode.too_many_outputs);
        Mockito.verifyNoInteractions(vault);
    }
}
