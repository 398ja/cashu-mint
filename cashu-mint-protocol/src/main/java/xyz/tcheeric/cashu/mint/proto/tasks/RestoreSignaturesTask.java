package xyz.tcheeric.cashu.mint.proto.tasks;

import java.util.ArrayList;
import java.util.List;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.service.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

public class RestoreSignaturesTask implements Task<PostRestoreResponse> {

    private final PostRestoreRequest request;
    private final SignatureVaultService signatureVaultService;

    public RestoreSignaturesTask(@NonNull PostRestoreRequest request) {
        this(request, new DefaultSignatureVaultService());
    }

    public RestoreSignaturesTask(@NonNull PostRestoreRequest request,
                                 @NonNull SignatureVaultService signatureVaultService) {
        this.request = request;
        this.signatureVaultService = signatureVaultService;
    }

    @Override
    public PostRestoreResponse execute() throws CashuErrorException {
        List<BlindedMessage> outputs = new ArrayList<>();
        List<BlindSignature> signatures = new ArrayList<>();
        for (BlindedMessage bm : request.getOutputs()) {
            BlindSignature sig = signatureVaultService.retrieve(bm);
            if (sig != null) {
                outputs.add(bm);
                signatures.add(sig);
            }
        }
        return new PostRestoreResponse(outputs, signatures);
    }
}
