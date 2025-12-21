package xyz.tcheeric.cashu.mint.rest.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;

import static org.mockito.Mockito.mock;

/**
 * Test configuration for VoucherNostrIT integration tests.
 *
 * <p>This configuration creates mocked beans for voucher ports to allow
 * testing without actual Nostr relay connections.
 */
@TestConfiguration
public class VoucherNostrITConfig {

    @Bean
    public VoucherLedgerPort voucherLedgerPort() {
        return mock(VoucherLedgerPort.class);
    }

    @Bean
    public VoucherBackupPort voucherBackupPort() {
        return mock(VoucherBackupPort.class);
    }

    @Bean
    public VoucherService voucherService(VoucherLedgerPort ledgerPort, VoucherBackupPort backupPort) {
        // Use test keys for voucher signing (ED25519 format)
        String issuerPrivateKey = "0000000000000000000000000000000000000000000000000000000000000001";
        // Matching BIP-340 x-only public key for the above private key (G * 1)
        String issuerPublicKey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

        return new VoucherService(
            ledgerPort,
            backupPort,
            issuerPrivateKey,
            issuerPublicKey
        );
    }
}
