package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.crypto.SpentProofKey;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * A proof lookup must name the mint it belongs to.
 *
 * <p>Two reasons, one of which is a correctness bug rather than a performance concern.
 *
 * <p>Correctness: {@code secret} is not unique on its own. The uniqueness constraint is
 * {@code (mint_id, secret)}, so an unscoped lookup can return a different mint's proof for the
 * same secret, and the caller then reads another mint's state as its own.
 *
 * <p>Performance: {@code t_proof} has no standalone index on {@code secret}. The only index
 * covering it is {@code uk_proof_mint_secret (mint_id, secret)}, and a B-tree cannot serve a
 * predicate on its second column, so the unscoped query sequentially scans the whole table.
 * Measured on staging at 13,011 rows: 754 buffers and 2.4ms, against 5 buffers and 0.076ms for
 * the scoped form, and 4.67 full table scans per proof written. The unscoped cost is linear in
 * table size (1.70ms at 12.9k rows, 16.3ms at 100k, 174ms at 1M) while the scoped form is flat.
 * See cashu-vault#153.
 */
@DisplayName("proof lookups are scoped to a mint")
class ProofLookupIsMintScopedTest {

    private static final UUID MINT_ID = UUID.fromString("1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae");
    private static final String SECRET = "a5c0e0a5e9e3d2c1b0a9f8e7d6c5b4a3928170615043f2e1d0c9b8a796857463";

    private final DefaultProofVaultService service = new DefaultProofVaultService();

    @Nested
    @DisplayName("retrieveProof")
    class RetrieveProof {

        /** The lookup must reach the vault's mint-scoped form, which uses uk_proof_mint_secret. */
        @Test
        @DisplayName("queries the vault scoped to the mint")
        void queriesTheVaultScopedToTheMint() throws Exception {
            ProofEntity stored = new ProofEntity();
            try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
                vault.when(() -> DBProofVault.retrieveProof(eq(MINT_ID.toString()), Mockito.anyString()))
                        .thenReturn(stored);

                assertThat(service.retrieveProof(MINT_ID, SECRET)).isSameAs(stored);

                vault.verify(() -> DBProofVault.retrieveProof(eq(MINT_ID.toString()), Mockito.anyString()));
            }
        }

        /**
         * The unscoped single-argument overload must not be reached. This is the assertion that
         * fails before the fix, and it is phrased as "never" rather than "not on this path"
         * because a global secret lookup is unsafe regardless of which caller performs it.
         */
        @Test
        @DisplayName("never falls back to an unscoped lookup")
        void neverFallsBackToAnUnscopedLookup() throws Exception {
            try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
                vault.when(() -> DBProofVault.retrieveProof(eq(MINT_ID.toString()), Mockito.anyString()))
                        .thenReturn(null);

                service.retrieveProof(MINT_ID, SECRET);

                vault.verify(() -> DBProofVault.retrieveProof(Mockito.anyString()), Mockito.never());
            }
        }

        /**
         * Both NUT-00 encodings still have to be tried. A proof spent before the secret encoding
         * was corrected lives under the legacy curve point, and reporting it unspent would permit
         * a second spend. Scoping the lookup must not quietly drop that search.
         */
        @Test
        @DisplayName("still checks both NUT-00 encodings before concluding a proof is unspent")
        void stillChecksBothEncodings() throws Exception {
            String legacyKey = SpentProofKey.lookupKeys(SECRET).get(0);
            String otherKey = SpentProofKey.lookupKeys(SECRET).get(1);
            ProofEntity storedUnderTheSecondKey = new ProofEntity();

            try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
                vault.when(() -> DBProofVault.retrieveProof(MINT_ID.toString(), legacyKey))
                        .thenReturn(null);
                vault.when(() -> DBProofVault.retrieveProof(MINT_ID.toString(), otherKey))
                        .thenReturn(storedUnderTheSecondKey);

                assertThat(service.retrieveProof(MINT_ID, SECRET))
                        .as("a proof recorded under the second candidate key must still be found")
                        .isSameAs(storedUnderTheSecondKey);
            }
        }
    }

    @Nested
    @DisplayName("storageKeyFor")
    class StorageKeyFor {

        /** Recording a spend must look for the existing row within this mint, not globally. */
        @Test
        @DisplayName("resolves the existing key scoped to the mint")
        void resolvesTheExistingKeyScopedToTheMint() throws Exception {
            String legacyKey = SpentProofKey.lookupKeys(SECRET).get(0);

            try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
                vault.when(() -> DBProofVault.retrieveProof(MINT_ID.toString(), legacyKey))
                        .thenReturn(new ProofEntity());

                assertThat(service.storageKeyFor(MINT_ID, SECRET))
                        .as("the key an existing record already uses, found within this mint")
                        .isEqualTo(legacyKey);

                vault.verify(() -> DBProofVault.retrieveProof(Mockito.anyString()), Mockito.never());
            }
        }

        /** With no existing record, the spec issuance key is used. */
        @Test
        @DisplayName("falls back to the spec issuance key when the proof is unknown")
        void fallsBackToTheIssuanceKey() throws Exception {
            try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
                vault.when(() -> DBProofVault.retrieveProof(Mockito.anyString(), Mockito.anyString()))
                        .thenReturn(null);

                assertThat(service.storageKeyFor(MINT_ID, SECRET))
                        .isEqualTo(SpentProofKey.issuanceKey(SECRET));
            }
        }
    }

    /**
     * The interface default exists so test doubles and legacy contexts keep compiling. It must
     * still be mint-scoped in shape, so a caller cannot satisfy the signature by discarding the
     * mint.
     */
    @Test
    @DisplayName("the interface default accepts a mint and returns the issuance key")
    void interfaceDefaultAcceptsAMint() throws CashuErrorException {
        ProofVaultService bare = new ProofVaultService() {
            @Override
            public void store(ProofEntity proofEntity) {
            }

            @Override
            public void invalidate(ProofEntity proofEntity) {
            }

            @Override
            public void archive(ProofEntity proofEntity) {
            }

            @Override
            public void storePending(ProofEntity proofEntity) {
            }

            @Override
            public ProofEntity retrieveProof(UUID mintId, String secret) {
                return null;
            }

            @Override
            public ProofEntity retrieveProofByY(String yHex) {
                return null;
            }
        };

        assertThat(bare.storageKeyFor(MINT_ID, SECRET))
                .isEqualTo(SpentProofKey.issuanceKey(SECRET));
    }
}
