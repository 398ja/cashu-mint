package xyz.tcheeric.cashu.mint.admin.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the administrative REST service.
 */
// Scan the admin packages plus the vault api/hashi packages so HashiVaultRegistrar
// is picked up and VaultClientFactory resolves to the HashiCorp backend when
// vault.hashi.enabled=true. Without the wider scan the registrar never runs, the
// factory falls back to the database vault, and provisioning writes private keys
// into the vault database in plaintext while the mint looks for them in HashiCorp.
// Mirrors the scan CashuMintRestApplication performs for the same reason.
@SpringBootApplication(scanBasePackages = {
        "xyz.tcheeric.cashu.mint.admin.rest",
        "xyz.tcheeric.cashu.vault.api",
        "xyz.tcheeric.cashu.vault.hashi"
})
public class CashuMintAdminRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintAdminRestApplication.class, args);
    }
}
