package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.RestoreSignaturesTask;

@Nut(value = 9, description = "Restore signatures")
public class NUT09 {

    public static PostRestoreResponse restore(@NonNull PostRestoreRequest request,
                                              @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return new RestoreSignaturesTask(request, signatureVaultService).execute();
    }
}
