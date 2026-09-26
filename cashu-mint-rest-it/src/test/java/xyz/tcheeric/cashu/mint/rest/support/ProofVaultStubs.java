package xyz.tcheeric.cashu.mint.rest.support;

import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Stubs a mocked {@link ProofVaultService} to key proofs as the real service does for a proof it
 * has never seen: under the spec issuance key.
 *
 * <p>An unstubbed mock answers {@code storageKeyFor} with null. Before #487 that null was written
 * into every hold row as its key and nothing noticed; now the key is a {@code StorageKey} and a null
 * one fails the claim, so every IT that mocks the vault and exercises a swap or melt needs this.
 */
public final class ProofVaultStubs {

    private ProofVaultStubs() {
    }

    /** Makes {@code storageKeyFor} on the given mock return each secret's issuance key. */
    public static void keyProofsByIssuanceKey(ProofVaultService mockedVault) throws CashuErrorException {
        when(mockedVault.storageKeyFor(any(UUID.class), any(ProofSecret.class)))
                .thenAnswer(call -> SpentProofKey.issuanceKey(call.getArgument(1, ProofSecret.class)));
    }
}
