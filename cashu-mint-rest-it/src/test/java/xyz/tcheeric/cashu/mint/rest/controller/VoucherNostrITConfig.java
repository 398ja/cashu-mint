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
        String issuerPublicKey = "4cb5abf6ad79fbf5abbccafcc269d85cd2651ed4b885b5869f241aedf0a5ba29";

        return new VoucherService(
            ledgerPort,
            backupPort,
            issuerPrivateKey,
            issuerPublicKey
        );
    }
}
