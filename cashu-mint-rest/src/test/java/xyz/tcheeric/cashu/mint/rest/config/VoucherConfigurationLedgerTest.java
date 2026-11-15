package xyz.tcheeric.cashu.mint.rest.config;

import nostr.id.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The mint publishes and reads no v1 voucher-ledger events (imani-gateway-customer#310), and the
 * issuer key pair VoucherService signs with is still checked at startup.
 */
class VoucherConfigurationLedgerTest {

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
    @DisplayName("the ledger port is a no-op: nothing published, no status known")
    void ledgerIsNoOp() {
        VoucherLedgerPort ledger = new VoucherConfiguration(propertiesWith(PRIVKEY, derivedPubKey()))
                .voucherLedgerPort();

        assertThat(ledger).isInstanceOf(NoOpVoucherLedgerPort.class);
        ledger.updateStatus("v", VoucherStatus.REDEEMED);
        assertThat(ledger.queryStatus("v")).isEmpty();
        assertThat(ledger.exists("v")).isFalse();
    }

    @Test
    @DisplayName("a matching key pair builds the voucher service")
    void matchingPairBuilds() {
        VoucherConfiguration config = new VoucherConfiguration(propertiesWith(PRIVKEY, derivedPubKey()));

        assertThat(config.voucherService(config.voucherLedgerPort(), mock(VoucherBackupPort.class)))
                .isNotNull();
    }

    @Test
    @DisplayName("a private key that does not derive the configured pubkey is refused")
    void mismatchedPairIsRefused() {
        String otherPriv = "0000000000000000000000000000000000000000000000000000000000000042";
        VoucherConfiguration config = new VoucherConfiguration(propertiesWith(otherPriv, derivedPubKey()));

        assertThatThrownBy(() -> config.voucherService(config.voucherLedgerPort(), mock(VoucherBackupPort.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not derive");
    }

    @Test
    @DisplayName("a missing private key fails at startup")
    void missingPrivateKeyFailsFast() {
        VoucherConfiguration config = new VoucherConfiguration(propertiesWith("  ", derivedPubKey()));

        assertThatThrownBy(() -> config.voucherService(config.voucherLedgerPort(), mock(VoucherBackupPort.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuerPrivateKey");
    }
}
