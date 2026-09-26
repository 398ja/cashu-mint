package xyz.tcheeric.cashu.mint.rest.config;

import nostr.id.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.voucher.nostr.NostrClientAdapter;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The mint's voucher ledger must be built with an identity it can SIGN with.
 *
 * <p>NostrVoucherLedgerRepository refuses to publish without one — correctly,
 * since an unsigned ledger event is not evidence of anything and relays reject
 * it. Built from the public key alone it threw "This repository has no signing
 * identity" on every publish. Because that happens on the publish path and not
 * at startup, the mint booted clean and only failed once a voucher existed,
 * with the error logged on a background thread while callers saw success.
 *
 * <p>The same defect was live in the customer gateway, where a single boot
 * logged 12 lost publishes. Here it was masked only because voucher.enabled is
 * off in the test stack, which is exactly the kind of thing that stays hidden
 * until the day the feature is switched on.
 */
class VoucherConfigurationLedgerSigningTest {

    /** A real secp256k1 keypair; the public key is derived, never hardcoded. */
    private static final String PRIVKEY =
            "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a";

    private VoucherProperties propertiesWith(String privKey, String pubKey) {
        VoucherProperties props = new VoucherProperties();
        VoucherProperties.Mint mint = new VoucherProperties.Mint();
        mint.setIssuerPrivateKey(privKey);
        mint.setIssuerPublicKey(pubKey);
        props.setMint(mint);
        return props;
    }

    private static String derivedPubKey() {
        return Identity.create(new nostr.base.PrivateKey(PRIVKEY)).getPublicKey().toString();
    }

    @Test
    @DisplayName("the ledger is built with a signing identity, not just a pubkey")
    void ledgerCanSign() throws Exception {
        VoucherConfiguration config = new VoucherConfiguration(
                propertiesWith(PRIVKEY, derivedPubKey()));

        Object repository = config.voucherLedgerPort(mock(NostrClientAdapter.class));

        Field f = repository.getClass().getDeclaredField("issuerIdentity");
        f.setAccessible(true);
        assertThat(f.get(repository))
                .as("a ledger with no identity cannot publish, and fails once per voucher")
                .isNotNull();
    }

    @Test
    @DisplayName("a private key that does not derive the configured pubkey is refused")
    void mismatchedPairIsRefused() {
        // A valid key, but not the pair of derivedPubKey().
        String otherPriv = "0000000000000000000000000000000000000000000000000000000000000042";

        assertThatThrownBy(() -> new VoucherConfiguration(
                propertiesWith(otherPriv, derivedPubKey()))
                .voucherLedgerPort(mock(NostrClientAdapter.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not derive");
    }

    @Test
    @DisplayName("a missing private key fails at startup, not on the first voucher")
    void missingPrivateKeyFailsFast() {
        assertThatThrownBy(() -> new VoucherConfiguration(
                propertiesWith("  ", derivedPubKey()))
                .voucherLedgerPort(mock(NostrClientAdapter.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuerPrivateKey");
    }
}
