package xyz.tcheeric.cashu.mint.proto.tasks;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoucherSecretDetector} decides which spending condition a proof gets, so its answers
 * are a security boundary rather than a classification convenience.
 *
 * <p>The distinction these tests pin: a {@code P2PK_VOUCHER} is a voucher, but it must
 * <em>not</em> be reported by {@link VoucherSecretDetector#isUnlockedVoucherSecret(Secret)}. That
 * predicate selects the voucher-only condition, which never checks a witness — so answering
 * true there would enforce the issuer signature and expiry while silently ignoring the lock,
 * which is exactly the failure the separate kind was introduced to prevent.
 */
class VoucherSecretDetectorTest {

    /** secp256k1 generator, even-y. */
    private static final String SPENDING_KEY =
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private static P2PKVoucherSecret lockedVoucher() {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(SPENDING_KEY));
        secret.setIssuerId("acme");
        secret.setVoucherId(UUID.randomUUID().toString());
        return secret;
    }

    private static VoucherSecret plainVoucher() {
        return VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("acme")
                .unit("sat")
                .faceValue(5000L)
                .build();
    }

    @Nested
    @DisplayName("a P2PK-locked voucher")
    class LockedVoucher {

        @Test
        @DisplayName("is reported by isP2PKVoucherSecret")
        void detected() {
            assertThat(VoucherSecretDetector.isP2PKVoucherSecret(lockedVoucher())).isTrue();
        }

        @Test
        @DisplayName("is NOT reported by isUnlockedVoucherSecret, which selects the witness-free path")
        void notReportedAsPlainVoucher() {
            // The load-bearing assertion. If this ever returns true, the swap dispatcher's
            // voucher branch will claim the proof and its lock will never be checked.
            assertThat(VoucherSecretDetector.isUnlockedVoucherSecret(lockedVoucher())).isFalse();
        }

        @Test
        @DisplayName("IS reported by carriesVoucherMetadata, the honest voucher question")
        void reportedAsCarryingVoucherMetadata() {
            // The counterpart to the assertion above. isUnlockedVoucherSecret answers a
            // dispatch question and says no; this answers "is it a voucher" and says yes.
            // Model B and the mixed-proof rule use this one.
            assertThat(VoucherSecretDetector.carriesVoucherMetadata(lockedVoucher())).isTrue();
        }

        @Test
        @DisplayName("is a P2PKSecret, which is why dispatch order matters")
        void isAlsoAP2PKSecret() {
            // An `instanceof P2PKSecret` branch matches it, so P2PK_VOUCHER has to be tested
            // for first or the lock is enforced while the voucher checks are skipped.
            assertThat(lockedVoucher()).isInstanceOf(P2PKSecret.class);
        }
    }

    @Nested
    @DisplayName("an ordinary voucher")
    class PlainVoucher {

        @Test
        @DisplayName("is reported by isUnlockedVoucherSecret")
        void detected() {
            assertThat(VoucherSecretDetector.isUnlockedVoucherSecret(plainVoucher())).isTrue();
        }

        @Test
        @DisplayName("is not reported as P2PK-locked")
        void notReportedAsLocked() {
            assertThat(VoucherSecretDetector.isP2PKVoucherSecret(plainVoucher())).isFalse();
        }

        @Test
        @DisplayName("is reported by carriesVoucherMetadata too")
        void reportedAsCarryingVoucherMetadata() {
            assertThat(VoucherSecretDetector.carriesVoucherMetadata(plainVoucher())).isTrue();
        }
    }

    @Nested
    @DisplayName("non-voucher secrets")
    class NonVouchers {

        @Test
        @DisplayName("a plain P2PK secret is neither kind of voucher")
        void plainP2PK() {
            P2PKSecret secret = new P2PKSecret(Hex.decode(SPENDING_KEY));

            assertThat(VoucherSecretDetector.isUnlockedVoucherSecret(secret)).isFalse();
            assertThat(VoucherSecretDetector.isP2PKVoucherSecret(secret)).isFalse();
            assertThat(VoucherSecretDetector.carriesVoucherMetadata(secret)).isFalse();
        }

        @Test
        @DisplayName("a random-string secret is neither")
        void randomString() {
            Secret secret = RandomStringSecret.create();

            assertThat(VoucherSecretDetector.isUnlockedVoucherSecret(secret)).isFalse();
            assertThat(VoucherSecretDetector.isP2PKVoucherSecret(secret)).isFalse();
        }

        @Test
        @DisplayName("null is neither, rather than throwing")
        void nullSecret() {
            assertThat(VoucherSecretDetector.isUnlockedVoucherSecret(null)).isFalse();
            assertThat(VoucherSecretDetector.isP2PKVoucherSecret(null)).isFalse();
        }
    }
}
