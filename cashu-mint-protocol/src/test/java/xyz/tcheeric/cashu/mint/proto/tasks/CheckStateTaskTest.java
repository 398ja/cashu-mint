package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CheckStateTaskTest {

    @Test
    public void executeSuccess() throws CashuErrorException {
        UUID mintId = UUID.randomUUID();
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        when(request.getHashToCurveSecrets()).thenReturn(List.of("secret"));

        MintProtocolService mintProtocolService = Mockito.mock(MintProtocolService.class);
        MintEntity mintEntity = Mockito.mock(MintEntity.class);
        when(mintProtocolService.toMintEntity(any(Mint.class))).thenReturn(mintEntity);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        ProofEntity proofEntity = Mockito.mock(ProofEntity.class);
        when(proofVaultService.retrieveProof("secret")).thenReturn(proofEntity);
        when(proofEntity.getState()).thenReturn(ProofEntity.STATE_PENDING);
        when(proofEntity.getWitness()).thenReturn("wit");

        CheckStateTask task = new CheckStateTask(mintId, request, mintProtocolService, proofVaultService, mintVaultService);
        PostCheckStateResponse response = task.execute();

        verify(mintVaultService).load(mintEntity, false, true);
        verify(proofVaultService).retrieveProof("secret");

        assertEquals(1, response.getResponseStates().size());
        PostCheckStateResponse.ResponseState state = response.getResponseStates().get(0);
        assertEquals(NUT07.UNSPENT, state.getState());
        assertEquals("secret", state.getHashToCurveSecret());
        assertEquals("wit", state.getWitness());
    }
}
