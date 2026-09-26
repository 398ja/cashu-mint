package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.HashToCurveSecret;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class CheckStateTaskTest {

    // Confirms that an existing proof marked as pending is returned with pending state and witness.
    @Test
    public void executeSuccess() throws CashuErrorException {
        UUID mintId = UUID.randomUUID();
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        HashToCurveSecret secret = HashToCurveSecret.fromString("02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee");
        when(request.getHashToCurveSecrets()).thenReturn(List.of(secret));

        MintProtocolService mintProtocolService = Mockito.mock(MintProtocolService.class);
        MintEntity mintEntity = Mockito.mock(MintEntity.class);
        when(mintProtocolService.toMintEntity(any(Mint.class))).thenReturn(mintEntity);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        ProofEntity proofEntity = Mockito.mock(ProofEntity.class);
        when(proofVaultService.retrieveProof(StorageKey.of(secret))).thenReturn(proofEntity);
        when(proofEntity.getState()).thenReturn(ProofEntity.STATE_PENDING);
        when(proofEntity.getWitness()).thenReturn("wit");

        CheckStateTask task = new CheckStateTask(mintId, request, mintProtocolService, proofVaultService, mintVaultService);
        PostCheckStateResponse response = task.execute();

        verify(mintVaultService).load(mintEntity, false, true);
        verify(proofVaultService).retrieveProof(StorageKey.of(secret));

        assertEquals(1, response.getStates().size());
        PostCheckStateResponse.ResponseState state = response.getStates().get(0);
        assertEquals(NUT07.PENDING, state.getState());
        assertEquals(secret, state.getHashToCurveSecret());
        assertEquals("wit", state.getWitness());
    }

    // Ensures unexpected vault failures propagate to the caller.
    @Test
    public void executeNotFoundError() throws CashuErrorException {
        // Setup
        UUID mintId = UUID.randomUUID();
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        HashToCurveSecret secret = HashToCurveSecret.fromString("02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee");
        when(request.getHashToCurveSecrets()).thenReturn(List.of(secret));

        MintProtocolService mintProtocolService = Mockito.mock(MintProtocolService.class);
        MintEntity mintEntity = Mockito.mock(MintEntity.class);
        when(mintProtocolService.toMintEntity(any(Mint.class))).thenReturn(mintEntity);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);

        // Mock the ProofVaultService to throw a runtime error
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        when(proofVaultService.retrieveProof(StorageKey.of(secret)))
                .thenThrow(new IllegalStateException("fail"));

        // Execute
        CheckStateTask task = new CheckStateTask(mintId, request, mintProtocolService, proofVaultService, mintVaultService);
        assertThrows(IllegalStateException.class, task::execute);

        verify(mintVaultService).load(mintEntity, false, true);
        verify(proofVaultService).retrieveProof(StorageKey.of(secret));
    }

    // Ensures missing proofs are reported as unspent when the vault returns null.
    @Test
    public void executeNotFoundNull() throws CashuErrorException {
        UUID mintId = UUID.randomUUID();
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        HashToCurveSecret secret = HashToCurveSecret.fromString("03599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee");
        when(request.getHashToCurveSecrets()).thenReturn(List.of(secret));

        MintProtocolService mintProtocolService = Mockito.mock(MintProtocolService.class);
        MintEntity mintEntity = Mockito.mock(MintEntity.class);
        when(mintProtocolService.toMintEntity(any(Mint.class))).thenReturn(mintEntity);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);

        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        when(proofVaultService.retrieveProof(StorageKey.of(secret))).thenReturn(null);

        CheckStateTask task = new CheckStateTask(mintId, request, mintProtocolService, proofVaultService, mintVaultService);
        PostCheckStateResponse response = task.execute();

        verify(mintVaultService).load(mintEntity, false, true);
        verify(proofVaultService).retrieveProof(StorageKey.of(secret));

        PostCheckStateResponse.ResponseState state = response.getStates().get(0);
        assertEquals(NUT07.UNSPENT, state.getState());
        assertEquals(secret, state.getHashToCurveSecret());
        assertNull(state.getWitness());
    }

    // Confirms that a request carrying more Ys than the declared maximum is refused with
    // too_many_inputs, and that the vault is never consulted: the task performs one lookup per Y
    // and is run once per mint, so an unbounded list is an amplification attack on the vault.
    @Test
    public void refusesMoreSecretsThanTheMaximumWithoutTouchingTheVault() {
        UUID mintId = UUID.randomUUID();
        HashToCurveSecret secret = HashToCurveSecret.fromString(
                "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee");
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        when(request.getHashToCurveSecrets())
                .thenReturn(Collections.nCopies(PostCheckStateRequest.MAX_SECRETS + 1, secret));

        MintProtocolService mintProtocolService = Mockito.mock(MintProtocolService.class);
        MintVaultService mintVaultService = Mockito.mock(MintVaultService.class);
        ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);

        CheckStateTask task = new CheckStateTask(
                mintId, request, mintProtocolService, proofVaultService, mintVaultService);

        CashuErrorException thrown = assertThrows(CashuErrorException.class, task::execute);
        assertEquals(CashuErrorCode.too_many_inputs, thrown.getErrorCode());
        verifyNoInteractions(proofVaultService, mintVaultService);
    }
}
