package xyz.tcheeric.cashu.mint.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.InputStream;
import java.util.Properties;

// Scan mint packages + the vault-hashi config/client packages so HashiVaultRegistrar
// (which flips VaultClientFactory to HASHICORP at startup) and HashiVaultClient get
// picked up when vault.hashi.enabled=true. Without the wider scan, MintProtocolUtil's
// VaultClientFactory.keyVault() returns DBKeyVault (no Hashi enrichment) and signing
// fails with KeyEntity.privateKey=null — the silent refund pattern observed
// 2026-05-24 on staging. The vault-JPA controllers/repos that the original narrow
// scan was excluding live under xyz.tcheeric.cashu.vault.db.{controller,repos}, which
// the mint-rest classpath doesn't have (cashu-vault-jpa is a runtime-only sibling
// service), so widening doesn't accidentally pull them in here.
@Slf4j
@SpringBootApplication(scanBasePackages = {
        "xyz.tcheeric.cashu.mint",
        "xyz.tcheeric.cashu.vault.api",
        "xyz.tcheeric.cashu.vault.hashi"
})
@EnableScheduling
public class CashuMintRestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashuMintRestApplication.class, args);
    }

    @Bean
    ApplicationRunner diagnostics() {
        return args -> {
            try {
                try (InputStream in = CashuMintRestApplication.class.getClassLoader().getResourceAsStream("app.properties")) {
                    if (in != null) {
                        Properties p = new Properties();
                        p.load(in);
                        log.info("Diagnostics: classpath app.properties gateway.bolt11={} gateway.bolt11.sat={}",
                                p.getProperty("gateway.bolt11"), p.getProperty("gateway.bolt11.sat"));
                    } else {
                        log.warn("Diagnostics: classpath app.properties not found by application classloader");
                    }
                }

                try {
                    Class<?> clazz = Class.forName("xyz.tcheeric.payment.adapter.ln.phoenixd.PhoenixdGateway");
                    Package pkg = clazz.getPackage();
                    log.info("Diagnostics: PhoenixdGateway present. package={} version={}",
                            (pkg != null ? pkg.getName() : "n/a"), (pkg != null ? pkg.getImplementationVersion() : "n/a"));
                } catch (Throwable t) {
                    log.warn("Diagnostics: PhoenixdGateway class not found on classpath");
                }
            } catch (Exception e) {
                log.warn("Diagnostics: failed to collect startup diagnostics", e);
            }
        };
    }
}
