package xyz.tcheeric.cashu.mint.proto.crypto;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.SecretEncoding;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The double-spend guard across the NUT-00 secret encoding migration.
 *
 * <p>The mint records a spent proof under {@code Y = hash_to_curve(secret)}. cashu-lib 0.22.0
 * changed that encoding from hex-decoding the secret to hashing its UTF-8 bytes, so the same
 * legacy proof now computes a different Y. A store that looked only under the new Y would find no
 * row for an already-spent legacy proof and report it unspent, letting it be spent a second time.
 *
 * <p>These tests are the standing instrument for that hole.
 */
public class LegacyProofDoubleSpendTest {

    /** A 64-char hex secret: the shape that hex-decodes, so the two encodings genuinely differ. */
    private static final String LEGACY_HEX_SECRET =
            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    private static String yUnder(SecretEncoding encoding) {
        return PublicKey.fromBytes(BDHKEUtils.hashToCurve(LEGACY_HEX_SECRET, encoding)).toString();
    }

    /**
     * The premise of the whole migration: for a hex secret the spec encoding and the legacy
     * encoding really do produce different curve points. If this ever stopped being true the
     * double-spend hole would not exist and the dual-key machinery could be deleted.
     */
    @Test
    public void specAndLegacyEncodingsDisagreeOnAHexSecret() {
        assertNotEquals(yUnder(SecretEncoding.SPEC), yUnder(SecretEncoding.LEGACY_HEX),
                "A hex secret must hash to two different points, otherwise there is nothing to migrate.");
    }

    /**
     * THE test this migration exists for. A proof that was spent before the upgrade is recorded in
     * the vault under the LEGACY_HEX point only. Looking it up after the upgrade must still find
     * it, so the double-spend check reports SPENT rather than waving the proof through a second
     * time.
     */
    @Test
    public void alreadySpentLegacyProofIsStillDetectedAsSpentAfterTheUpgrade() throws CashuErrorException {
        String legacyY = yUnder(SecretEncoding.LEGACY_HEX);
        String specY = yUnder(SecretEncoding.SPEC);

        ProofEntity spentLegacyRow = new ProofEntity();
        spentLegacyRow.setSecret(legacyY);
        spentLegacyRow.setState(ProofEntity.STATE_SPENT);

        try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
            // The vault as it stands after the upgrade: the row exists ONLY under the legacy point.
            vault.when(() -> DBProofVault.retrieveProof(specY)).thenReturn(null);
            vault.when(() -> DBProofVault.retrieveProof(legacyY)).thenReturn(spentLegacyRow);

            ProofEntity found = new DefaultProofVaultService().retrieveProof(LEGACY_HEX_SECRET);

            assertNotNull(found,
                    "An already-spent legacy proof looked up after the upgrade must still be found. "
                            + "Returning null here is the double-spend hole: the caller concludes UNSPENT.");
            assertEquals(ProofEntity.STATE_SPENT, found.getState());
            assertSame(spentLegacyRow, found);
        }
    }

    /**
     * The lookup tries the spec point first, so the legacy path is exercised only by genuinely old
     * proofs and costs a second query only when the first misses.
     */
    @Test
    public void lookupChecksTheSpecKeyBeforeTheLegacyKey() throws CashuErrorException {
        String specY = yUnder(SecretEncoding.SPEC);
        ProofEntity specRow = new ProofEntity();
        specRow.setState(ProofEntity.STATE_SPENT);

        try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
            vault.when(() -> DBProofVault.retrieveProof(specY)).thenReturn(specRow);

            assertSame(specRow, new DefaultProofVaultService().retrieveProof(LEGACY_HEX_SECRET));

            vault.verify(() -> DBProofVault.retrieveProof(specY));
            vault.verify(() -> DBProofVault.retrieveProof(yUnder(SecretEncoding.LEGACY_HEX)),
                    Mockito.never());
        }
    }

    /**
     * A proof the mint has never seen is recorded under the spec point, so newly spent proofs stop
     * adding to the legacy population and it drains as old proofs are spent.
     */
    @Test
    public void anUnseenProofIsRecordedUnderTheSpecKey() throws CashuErrorException {
        try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
            vault.when(() -> DBProofVault.retrieveProof(Mockito.anyString())).thenReturn(null);

            assertEquals(yUnder(SecretEncoding.SPEC),
                    new DefaultProofVaultService().storageKeyFor(LEGACY_HEX_SECRET),
                    "A proof with no prior record must be stored under the spec encoding.");
        }
    }

    /**
     * A proof already recorded under the legacy point keeps that key when its spend is written, so
     * one logical proof never becomes two rows and the uniqueness the double-spend check relies on
     * survives the migration.
     */
    @Test
    public void aProofAlreadyRecordedUnderTheLegacyKeyKeepsIt() throws CashuErrorException {
        String legacyY = yUnder(SecretEncoding.LEGACY_HEX);

        try (MockedStatic<DBProofVault> vault = Mockito.mockStatic(DBProofVault.class)) {
            vault.when(() -> DBProofVault.retrieveProof(yUnder(SecretEncoding.SPEC))).thenReturn(null);
            vault.when(() -> DBProofVault.retrieveProof(legacyY)).thenReturn(new ProofEntity());

            assertEquals(legacyY,
                    new DefaultProofVaultService().storageKeyFor(LEGACY_HEX_SECRET),
                    "Writing the spend under the spec key would leave the legacy row untouched, "
                            + "and the proof would still look unspent to a legacy-keyed lookup.");
        }
    }

    /**
     * A NUT-10 well-known secret was always UTF-8 encoded, so both encodings agree and the lookup
     * collapses to a single key rather than querying the same point twice.
     */
    @Test
    public void aWellKnownSecretHasOnlyOneKey() {
        String wellKnown = "[\"P2PK\",{\"nonce\":\"abc\",\"data\":\"0249098aa8b9d2fbe4"
                + "6618c9f1df270cd08f4f2c3b0e1a3b0b3a3e3d3c3b3a39\"}]";
        assertEquals(1, SpentProofKey.lookupKeys(wellKnown).size(),
                "Both encodings UTF-8 encode a well-known secret, so there is only one point to check.");
    }

    /**
     * A secret that is neither hex nor well-known cannot have been issued under the legacy
     * encoding, so the fallback contributes no key and never widens a lookup.
     */
    @Test
    public void aNonHexPlainSecretHasOnlyTheSpecKey() {
        String nonHex = "not-a-hex-secret";
        assertEquals(1, SpentProofKey.lookupKeys(nonHex).size());
        assertTrue(SpentProofKey.lookupKeys(nonHex).contains(SpentProofKey.issuanceKey(nonHex)));
    }
}
