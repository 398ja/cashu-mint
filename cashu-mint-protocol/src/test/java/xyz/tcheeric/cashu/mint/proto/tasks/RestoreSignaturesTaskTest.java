package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        xyz.tcheeric.cashu.entities.rest.PostRestoreResponse expected =
                new xyz.tcheeric.cashu.entities.rest.PostRestoreResponse(List.of(message), List.of(signature));
        assertThat(response).isEqualTo(expected);
    }
}
