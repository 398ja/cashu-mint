package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The protocol validations shared by swap, mint and melt, and the error_codes.md codes they raise.
 */
public class ValidateTransactionTaskTest {

    private static final String SAT_KEYSET_ID = "0123456789abcdef";
    private static final String EUR_KEYSET_ID = "00fedcba98765432";
    private static final String ARCHIVED_KEYSET_ID = "00aaaaaaaaaaaaaa";
    private static final String FIRST_BLINDED_MESSAGE =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final String SECOND_BLINDED_MESSAGE =
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7";

    private final KeySet satKeySet = KeySet.builder().id(SAT_KEYSET_ID).unit("sat").build();
    private final KeySet eurKeySet = KeySet.builder().id(EUR_KEYSET_ID).unit("eur").build();
    private final KeySet archivedKeySet = KeySet.builder().id(ARCHIVED_KEYSET_ID).unit("sat").build();

    private MintLoadService mintLoadService() throws CashuErrorException {
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.keySet(SAT_KEYSET_ID)).thenReturn(satKeySet);
        Mockito.when(mintLoadService.keySet(EUR_KEYSET_ID)).thenReturn(eurKeySet);
        Mockito.when(mintLoadService.keySet(ARCHIVED_KEYSET_ID)).thenReturn(archivedKeySet);
        Mockito.when(mintLoadService.keySets(false)).thenReturn(List.of(satKeySet, eurKeySet));
        Mockito.when(mintLoadService.keySets(true)).thenReturn(List.of(archivedKeySet));
        return mintLoadService;
    }

    private RSSProof proof(int amount, String keySetId) {
        RSSProof proof = new RSSProof();
        proof.setAmount(amount);
        proof.setKeySetId(keySetId);
        proof.setSecret(RandomStringSecret.create());
        proof.setUnblindedSignature(SignatureTestData.sampleSignature());
        return proof;
    }

    private BlindedMessage output(int amount, String keySetId, String blindedMessage) {
        BlindedMessage message = new BlindedMessage();
        message.setAmount(amount);
        message.setKeySetId(KeysetId.fromString(keySetId));
        message.setBlindedMessage(PublicKey.fromString(blindedMessage));
        return message;
    }

    private ValidateTransactionTask<RandomStringSecret> task(List<RSSProof> inputs,
                                                             List<BlindedMessage> outputs,
                                                             SignatureVaultService signatureVault)
            throws CashuErrorException {
        return new ValidateTransactionTask<>(
                inputs == null ? null : List.copyOf(inputs),
                outputs,
                KeySetDirectory.of(mintLoadService()),
                signatureVault);
    }

    /** A well-formed single-unit transaction passes every rule. */
    @Test
    public void acceptsAWellFormedTransaction() throws CashuErrorException {
        var validation = task(
                List.of(proof(2, SAT_KEYSET_ID)),
                List.of(output(2, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertDoesNotThrow(validation::execute);
    }

    /** The same proof presented twice is refused with 11007 rather than counted twice. */
    @Test
    public void rejectsDuplicateInputsWith11007() throws CashuErrorException {
        RSSProof duplicated = proof(1000, SAT_KEYSET_ID);
        var validation = task(
                List.of(duplicated, duplicated),
                List.of(output(2000, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertEquals("duplicate_inputs", errorCodeOf(assertThrows(CashuErrorException.class, validation::execute)));
    }

    /** Two identical blinded messages are refused with 11008; the wallet could not use both signatures. */
    @Test
    public void rejectsDuplicateOutputsWith11008() throws CashuErrorException {
        var validation = task(
                List.of(proof(2, SAT_KEYSET_ID)),
                List.of(output(1, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE),
                        output(1, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertEquals("duplicate_outputs", errorCodeOf(assertThrows(CashuErrorException.class, validation::execute)));
    }

    /** Inputs drawn from two units are refused with 11009. */
    @Test
    public void rejectsMultipleInputUnitsWith11009() throws CashuErrorException {
        var validation = task(
                List.of(proof(1, SAT_KEYSET_ID), proof(1, EUR_KEYSET_ID)),
                List.of(output(2, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertEquals("multiple_units", errorCodeOf(assertThrows(CashuErrorException.class, validation::execute)));
    }

    /** A transaction whose outputs are in a different unit from its inputs is refused with 11010. */
    @Test
    public void rejectsInputsAndOutputsOfDifferentUnitsWith11010() throws CashuErrorException {
        var validation = task(
                List.of(proof(2, SAT_KEYSET_ID)),
                List.of(output(2, EUR_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertEquals("inputs_outputs_unit_mismatch", errorCodeOf(assertThrows(CashuErrorException.class, validation::execute)));
    }

    /** NUT-02 requires new outputs to come from an active keyset, so an archived one is refused with 12002. */
    @Test
    public void rejectsOutputsOnAnArchivedKeysetWith12002() throws CashuErrorException {
        var validation = task(
                List.of(proof(2, SAT_KEYSET_ID)),
                List.of(output(2, ARCHIVED_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertEquals("keyset_inactive", errorCodeOf(assertThrows(CashuErrorException.class, validation::execute)));
    }

    /** Inputs may still be spent from an archived keyset, which is how a wallet consolidates them. */
    @Test
    public void acceptsInputsFromAnArchivedKeyset() throws CashuErrorException {
        var validation = task(
                List.of(proof(2, ARCHIVED_KEYSET_ID)),
                List.of(output(2, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE)),
                null);

        assertDoesNotThrow(validation::execute);
    }

    /** A replayed output set is refused with 11003; NUT-09 restore is the way to recover it. */
    @Test
    public void rejectsPreviouslySignedOutputsWith11003() throws CashuErrorException {
        BlindedMessage alreadySigned = output(2, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE);
        SignatureVaultService signatureVault = Mockito.mock(SignatureVaultService.class);
        Mockito.when(signatureVault.retrieve(Mockito.any()))
                .thenReturn(Mockito.mock(BlindSignature.class));

        var validation = task(List.of(proof(2, SAT_KEYSET_ID)), List.of(alreadySigned), signatureVault);

        assertEquals("outputs_already_signed", errorCodeOf(assertThrows(CashuErrorException.class, validation::execute)));
    }

    /** An operation with outputs but no inputs, such as mint, is validated on its outputs alone. */
    @Test
    public void validatesOutputsWhenThereAreNoInputs() throws CashuErrorException {
        var validation = task(
                null,
                List.of(output(1, SAT_KEYSET_ID, FIRST_BLINDED_MESSAGE),
                        output(1, SAT_KEYSET_ID, SECOND_BLINDED_MESSAGE)),
                null);

        assertDoesNotThrow(validation::execute);
    }

    private static String errorCodeOf(CashuErrorException exception) {
        try {
            return new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class).code();
        } catch (Exception e) {
            throw new IllegalStateException("error payload was not the expected JSON", e);
        }
    }
}
