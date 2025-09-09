package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.mint.proto.service.DefaultSignatureVaultService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class RestoreSignaturesTaskTest {

    /**
     * Ensures that previously stored signatures are returned during restore.
     */
    @Test
    void restoresStoredSignatures() throws CashuErrorException {
        DefaultSignatureVaultService service = new DefaultSignatureVaultService();
        BlindedMessage message = Mockito.mock(BlindedMessage.class);
        PublicKey pk = Mockito.mock(PublicKey.class);
        Mockito.when(message.getBlindedMessage()).thenReturn(pk);
        Mockito.when(pk.toString()).thenReturn("pk1");
        BlindSignature signature = Mockito.mock(BlindSignature.class);
        service.store(message, signature);

        PostRestoreRequest request = new PostRestoreRequest(List.of(message));
        RestoreSignaturesTask task = new RestoreSignaturesTask(request, service);
        var response = task.execute();

        xyz.tcheeric.cashu.entities.rest.PostRestoreResponse expected =
                new xyz.tcheeric.cashu.entities.rest.PostRestoreResponse(List.of(message), List.of(signature));
        assertThat(response).isEqualTo(expected);
    }
}
