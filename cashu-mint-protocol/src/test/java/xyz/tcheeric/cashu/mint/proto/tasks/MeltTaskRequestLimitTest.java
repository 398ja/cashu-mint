package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SecurityLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * cashu-mint#521: a melt over the mint's input or output limit is refused as {@code too_many_inputs}
 * or {@code too_many_outputs}, as a swap is, before any keyset is loaded or proof looked up.
 */
class MeltTaskRequestLimitTest {

    private final MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
    private final ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

    // More inputs than the mint accepts: too_many_inputs (11014), which tells a wallet to merge its
    // proofs, and nothing is loaded or locked on the way to that answer.
    @Test
    @DisplayName("a melt with more inputs than the limit is refused as too_many_inputs")
    void tooManyInputs() {
        PostMeltRequest<RandomStringSecret> request = meltOf(SecurityLimits.MAX_PROOFS + 1, 0);

        assertThatThrownBy(() -> task(request).execute())
                .isInstanceOfSatisfying(CashuErrorException.class, refused ->
                        assertThat(refused.getErrorCode())
                                .isEqualTo(CashuErrorCode.too_many_inputs));
        verifyNoInteractions(mintLoadService, proofVaultService);
    }

    // More NUT-08 change outputs than the mint signs in one request: too_many_outputs (11015).
    @Test
    @DisplayName("a melt with more change outputs than the limit is refused as too_many_outputs")
    void tooManyOutputs() {
        PostMeltRequest<RandomStringSecret> request = meltOf(1, SecurityLimits.MAX_BLINDED_MESSAGES + 1);

        assertThatThrownBy(() -> task(request).execute())
                .isInstanceOfSatisfying(CashuErrorException.class, refused ->
                        assertThat(refused.getErrorCode())
                                .isEqualTo(CashuErrorCode.too_many_outputs));
        verifyNoInteractions(mintLoadService, proofVaultService);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MeltTask<RandomStringSecret> task(PostMeltRequest<RandomStringSecret> request) {
        return new MeltTask(request, PaymentMethod.MOCK, new Mint(UUID.randomUUID().toString()),
                Mockito.mock(MintProtocolService.class), mintLoadService, Mockito.mock(MintVaultService.class),
                proofVaultService);
    }

    private static PostMeltRequest<RandomStringSecret> meltOf(int inputs, int outputs) {
        List<Proof<RandomStringSecret>> proofs = new ArrayList<>();
        for (int i = 0; i < inputs; i++) {
            Proof<RandomStringSecret> proof = new Proof<>();
            proof.setAmount(1);
            proof.setSecret(RandomStringSecret.fromString("secret-" + i));
            proofs.add(proof);
        }
        List<BlindedMessage> change = new ArrayList<>();
        for (int i = 0; i < outputs; i++) {
            change.add(BlindedMessage.builder().amount(1).build());
        }
        PostMeltRequest<RandomStringSecret> request = new PostMeltRequest<>();
        request.setQuoteId("q-1");
        request.setInputs(proofs);
        request.setOutputs(change);
        return request;
    }
}
