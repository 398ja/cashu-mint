package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
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
 * The fix introduces {@link ProofVaultService#retrieveProofByY(String)} which
 * skips the hash step. These tests pin the contract.
 */
public class DefaultProofVaultServiceTest {

    /**
     * Pre-condition: confirm {@link SecretUtil#toYFromString(String)} is NOT
     * idempotent — i.e. hashing a Y point produces a different Y'. If this
     * assertion ever flips (e.g. someone makes toYFromString detect a Y on
     * input), the double-hash bug class disappears and this test can be
     * deleted alongside {@code retrieveProofByY}.
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
     * {@code retrieveProofByY} forwards its input directly to
     * {@link DBProofVault#retrieveProof(String)} without hashing.
     */
    @Test
    public void retrieveProofByYDoesNotHashInput() throws CashuErrorException {
        String yHex = "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";
        ProofEntity expected = new ProofEntity();

        try (MockedStatic<DBProofVault> mocked = Mockito.mockStatic(DBProofVault.class)) {
            mocked.when(() -> DBProofVault.retrieveProof(eq(yHex))).thenReturn(expected);

            DefaultProofVaultService svc = new DefaultProofVaultService();
            ProofEntity actual = svc.retrieveProofByY(yHex);

            assertSame(expected, actual, "retrieveProofByY must look up by raw Y, not the hash of Y");
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
            mocked.when(() -> DBProofVault.retrieveProof(eq(expectedY))).thenReturn(expected);

            DefaultProofVaultService svc = new DefaultProofVaultService();
            ProofEntity actual = svc.retrieveProof(rawSecret);

            assertSame(expected, actual);
            mocked.verify(() -> DBProofVault.retrieveProof(eq(expectedY)));
        }
    }
}
