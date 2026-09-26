package xyz.tcheeric.cashu.mint.proto.service;

import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;

/**
 * Mocks of {@link ProofVaultService} that key proofs the way the real service does.
 *
 * <p>A bare Mockito mock answers {@code storageKeyFor} with null, and hold rows used to be claimed
 * under that null key without anyone noticing. Since #487 the key is a {@code StorageKey}, so a
 * null one fails at the call site instead; tests that exercise the hold paths start from this.
 */
public final class ProofVaultServiceMocks {

    private ProofVaultServiceMocks() {
    }

    /** A mock whose {@code storageKeyFor} returns the spec issuance key, as for an unseen proof. */
    public static ProofVaultService keyingProofsByIssuanceKey() {
        ProofVaultService vault = Mockito.mock(ProofVaultService.class);
        try {
            Mockito.when(vault.storageKeyFor(any(UUID.class), any(ProofSecret.class)))
                    .thenAnswer(call -> SpentProofKey.issuanceKey(call.getArgument(1, ProofSecret.class)));
        } catch (CashuErrorException stubbingCannotThrow) {
            // Stubbing records the call on the mock; it never reaches an implementation.
            throw new IllegalStateException(stubbingCannotThrow);
        }
        return vault;
    }
}
