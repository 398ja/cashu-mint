package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.crypto.StorageKey;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.vault.api.db.impl.DBProofVault;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Regression coverage for the NUT-07 double-hash bug.
 * <p>
 * {@code retrieveProof(secret)} runs {@code SecretUtil.toYFromString} (=
 * {@code hashToCurve}) on its input before querying the DB. If a caller already
 * holds the hash-to-curve point Y (the NUT-07 wire shape) and routes it through
 * {@code retrieveProof}, the input is hashed a second time and the lookup
 * silently misses every stored proof — producing a spurious UNSPENT response.
 * <p>
 * The fix introduced a separate lookup by Y that skips the hash step, and #487 gave the two
 * lookups distinct argument types ({@link StorageKey}, {@link ProofSecret}) so the wrong one no
 * longer compiles. These tests pin the runtime contract behind the types.
 */
public class DefaultProofVaultServiceTest {

    /** Proof lookups are scoped per mint (cashu-vault#153). */
    private static final java.util.UUID MINT_ID =
            java.util.UUID.fromString("1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae");

    /**
     * Pre-condition: confirm {@link SecretUtil#toYFromString(String)} is NOT
     * idempotent — i.e. hashing a Y point produces a different Y'. If this
     * assertion ever flips (e.g. someone makes toYFromString detect a Y on
     * input), the double-hash bug class disappears and the lookup by Y is no
     * longer load-bearing.
     */
    @Test
    public void hashToCurveIsNotIdempotentOnY() {
        // A real compressed secp256k1 point on the curve.
        String yHex = "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";
        String hashed = SecretUtil.toYFromString(yHex);
        assertNotEquals(yHex, hashed,
                "If hashToCurve were idempotent on Y, retrieveProof would have happened to work for "
                        + "checkstate inputs by coincidence. It is not, so the bug exists by construction.");
    }

    /**
     * {@code retrieveProof(StorageKey)} forwards its key directly to
     * {@link DBProofVault#retrieveProof(String)} without hashing.
     *
     * <p>This lookup stays unscoped: a Y is globally unique by construction (it is a curve point
     * derived from the secret), so unlike the secret lookup it does not need a mint to be correct.
     * NUT-07 and NUT-17 both receive a bare list of Y values with no mint attached.
     */
    @Test
    public void aLookupByStorageKeyDoesNotHashIt() throws CashuErrorException {
        String yHex = "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";
        ProofEntity expected = new ProofEntity();

        try (MockedStatic<DBProofVault> mocked = Mockito.mockStatic(DBProofVault.class)) {
            mocked.when(() -> DBProofVault.retrieveProof(eq(yHex))).thenReturn(expected);

            DefaultProofVaultService svc = new DefaultProofVaultService();
            ProofEntity actual = svc.retrieveProof(StorageKey.of(yHex));

            assertSame(expected, actual, "A lookup by Y must use the raw Y, not the hash of Y");
            mocked.verify(() -> DBProofVault.retrieveProof(eq(yHex)));
        }
    }

    /**
     * The original {@code retrieveProof(secret)} still hashes its input — this
     * preserves the contract for callers that hold raw secret strings (the
     * spending-condition path).
     */
    @Test
    public void retrieveProofStillHashesInputForSpendingConditionCallers() throws CashuErrorException {
        String rawSecret = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        String expectedY = SecretUtil.toYFromString(rawSecret);
        ProofEntity expected = new ProofEntity();

        try (MockedStatic<DBProofVault> mocked = Mockito.mockStatic(DBProofVault.class)) {
            mocked.when(() -> DBProofVault.retrieveProof(eq(MINT_ID.toString()), eq(expectedY))).thenReturn(expected);

            DefaultProofVaultService svc = new DefaultProofVaultService();
            ProofEntity actual = svc.retrieveProof(MINT_ID, new ProofSecret(rawSecret));

            assertSame(expected, actual);
            mocked.verify(() -> DBProofVault.retrieveProof(eq(MINT_ID.toString()), eq(expectedY)));
        }
    }
}
