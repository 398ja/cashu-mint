package xyz.tcheeric.cashu.mint.proto.spending;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.P2PKProof;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #406: a past locktime with no refund keys unlocks a proof entirely.
 *
 * <p>NUT-11 says exactly that, and for a plain {@code P2PK} secret it is correct and
 * deliberate: an escrow whose locktime passes with no refund path falls open, which is what
 * makes the funds recoverable rather than burned. That behaviour is asserted here so the
 * distinction is visible, and so nobody "fixes" it into a spec violation.
 *
 * <p>For {@code P2PK_VOUCHER} the same rule is a footgun rather than a feature. That kind
 * exists for exactly one reason — the proof is worthless without the key it is locked to — and
 * a past locktime with no refund keys silently retracts that guarantee. Possession alone
 * becomes sufficient, which is the property NAP extension 0001 §3.1 depends on and the reason
 * the kind was created in preference to reusing {@code VOUCHER}.
 *
 * <p>The fix is at issuance rather than here: {@code VoucherIssuanceRules} refuses to mint such
 * a voucher, so the situation cannot be created. Verification keeps NUT-11 semantics unchanged,
 * because the mint must remain able to spend proofs issued by others.
 */
class P2PKLocktimeUnlockTest {

    /** A valid secp256k1 point, so construction does not fail for unrelated reasons. */
    private static final String LOCK_KEY =
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private final P2PKSpendingCondition condition =
            new P2PKSpendingCondition(Collections.emptyList());

    private P2PKProof lockedProof(P2PKSecret secret) {
        P2PKProof proof = new P2PKProof();
        proof.setAmount(8);
        proof.setKeySetId("00abc123def45678");
        proof.setSecret(secret);
        // Deliberately no witness: the whole question is whether one is required.
        return proof;
    }

    @Test
    void plainP2PKWithPastLocktimeAndNoRefundKeysIsUnlocked() {
        // NUT-11 conformant and intentional. Asserted so the behaviour is pinned rather than
        // incidental, and so the contrast with the voucher case below is explicit.
        P2PKSecret secret = new P2PKSecret(Hex.decode(LOCK_KEY));
        secret.setLockTime(1);

        assertDoesNotThrow(() -> condition.verify(lockedProof(secret)));
    }

    @Test
    void plainP2PKWithPastLocktimeAndRefundKeysStillRequiresARefundSignature() {
        // The other half of the rule: refund keys present means the refund path must actually
        // be satisfied. Without this, the test above would look like "locktime disables
        // everything", which is not what the spec says.
        P2PKSecret secret = new P2PKSecret(Hex.decode(LOCK_KEY));
        secret.setLockTime(1);
        secret.setRefund(List.of(LOCK_KEY));

        CashuErrorException thrown =
                assertThrows(CashuErrorException.class, () -> condition.verify(lockedProof(secret)));

        assertEquals("verify_invalid_refund_signature", thrown.getErrorCode().name());
    }

    @Test
    void aP2PKVoucherWithAPastLocktimeAndNoRefundKeysWouldAlsoBeUnlocked() {
        // The finding. This is the same NUT-11 path, reached through the voucher kind, and it
        // is why issuance refuses to create one: at verification time the lock is already gone,
        // and no amount of checking here can distinguish this proof from a legitimately
        // unlocked one.
        //
        // Asserted as-is rather than as a rejection, because changing verification for this
        // kind would deviate from NUT-11 for proofs the mint did not issue. The guarantee is
        // restored by making the voucher unissuable, not by making the proof unspendable.
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(LOCK_KEY));
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId("test-merchant");
        secret.setUnit("sat");
        secret.setFaceValue(1000L);
        secret.setLockTime(1);

        assertDoesNotThrow(() -> condition.verify(lockedProof(secret)),
                "documents the hazard the issuance rule exists to prevent");
    }
}
