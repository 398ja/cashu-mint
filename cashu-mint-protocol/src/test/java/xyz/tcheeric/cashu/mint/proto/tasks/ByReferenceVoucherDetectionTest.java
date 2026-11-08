package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.SecretUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cashu-voucher#64: a by-reference voucher secret ({@code data} is a 32-byte hash) is still a
 * voucher to every rule that asks "is this a voucher": the swap's mixed-proof-types rule, the
 * voucher swap balance, Model B in melt, and dispatch to the voucher spending condition. It must
 * never read as plain sats because its data is a hash.
 */
class ByReferenceVoucherDetectionTest {

    private static final Secret BY_REFERENCE = SecretUtil.toSecret(
            "[\"VOUCHER\",{\"nonce\":\"" + "ab".repeat(16) + "\",\"data\":\"" + "11".repeat(32)
                    + "\",\"tags\":[]}]");

    // A v2 secret is selected for the voucher spending condition, like a v1 one.
    @Test
    @DisplayName("a by-reference voucher is dispatched as an unlocked voucher")
    void isAnUnlockedVoucher() {
        assertThat(VoucherSecretDetector.isUnlockedVoucherSecret(BY_REFERENCE)).isTrue();
    }

    // A v2 secret carries voucher metadata for the mixed-proof and Model B rules.
    @Test
    @DisplayName("a by-reference voucher counts as a voucher for mixed-proof and melt rules")
    void carriesVoucherMetadata() {
        assertThat(VoucherSecretDetector.carriesVoucherMetadata(BY_REFERENCE)).isTrue();
    }
}
