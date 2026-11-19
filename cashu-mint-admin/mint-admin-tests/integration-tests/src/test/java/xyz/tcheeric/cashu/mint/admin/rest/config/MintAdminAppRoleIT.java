package xyz.tcheeric.cashu.mint.admin.rest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.Versioned;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import xyz.tcheeric.cashu.mint.admin.adapter.out.vault.KeysetMasterSeed;
import xyz.tcheeric.cashu.vault.hashi.config.HashiVaultConfig;

/**
 * mint-admin with its own least-privilege AppRole (cashu-mint#550), against a real Vault.
 *
 * <p>The deployment hands mint-admin a role-id and secret-id as files and loads them with
 * {@code spring.config.import=configtree:}, the same mechanism the compose files use. With the
 * {@code mint-admin} policy (vault/policies/cashu-mint-admin.hcl) it must read its keyset seed and
 * write keys, and Vault must refuse a seed overwrite, another seeds/ path and anything under sys/.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("mint-admin authenticates with its own AppRole and least-privilege policy")
class MintAdminAppRoleIT {

    private static final String ROOT = "mint-admin-approle-it-root";
    private static final String SEED_PATH = "seeds/mint-admin/keyset-master-seed";
    private static final String SEED = randomHex();

    private static String randomHex() {
        final byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    private static GenericContainer<?> vault;

    @TempDir
    static Path approleDir;

    @Configuration
    @Import(HashiVaultConfig.class)
    static class SeedContext {
    }

    @BeforeAll
    static void startVault() throws Exception {
        vault = new GenericContainer<>("hashicorp/vault:1.18")
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
                .withExposedPorts(8200)
                .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));
        vault.start();
        exec("vault secrets enable -path=cashu kv-v2");
        final String policy = Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("../../../vault/policies/cashu-mint-admin.hcl").normalize())
                .replace("@MOUNT@", "cashu");
        vault.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(policy), "/tmp/p.hcl");
        exec("vault policy write cashu-mint-admin /tmp/p.hcl");
        exec("vault auth enable approle");
        exec("vault write auth/approle/role/cashu-mint-admin token_policies=cashu-mint-admin token_ttl=1h");
        exec("vault kv metadata put -mount=cashu -cas-required=true " + SEED_PATH);
        exec("vault kv put -mount=cashu -cas=0 " + SEED_PATH + " seed=" + SEED);
        exec("vault kv put -mount=cashu seeds/other seed=x");
        Files.writeString(approleDir.resolve("vault.hashi.auth.approle.role-id"),
                exec("vault read -field=role_id auth/approle/role/cashu-mint-admin/role-id").trim());
        Files.writeString(approleDir.resolve("vault.hashi.auth.approle.secret-id"),
                exec("vault write -f -field=secret_id auth/approle/role/cashu-mint-admin/secret-id").trim());
    }

    @AfterAll
    static void stopVault() {
        if (vault != null) {
            vault.stop();
        }
    }

    private static String exec(final String command) throws Exception {
        final var r = vault.execInContainer("sh", "-c",
                "export VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=" + ROOT + "; " + command);
        assertThat(r.getExitCode()).as(command + ": " + r.getStderr()).isZero();
        return r.getStdout();
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(SeedContext.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.config.import=configtree:" + approleDir + "/",
                        "vault.hashi.enabled=true",
                        "vault.hashi.uri=http://" + vault.getHost() + ":" + vault.getMappedPort(8200),
                        "vault.hashi.auth.method=approle",
                        "vault.hashi.engine.mount=cashu")
                .run();
    }

    @Test
    @DisplayName("loads its keyset seed with the AppRole from configtree files, no token")
    void loadsTheSeedWithItsAppRole() {
        try (ConfigurableApplicationContext ctx = start()) {
            // The production wiring (OutboxSchedulerConfiguration.keysetMasterSeed), without the
            // outbox beans that need a database.
            final java.util.function.Supplier<KeysetMasterSeed> seed = OutboxSchedulerConfiguration.vaultSeed(
                    ctx.getBeanProvider(VaultTemplate.class), "cashu", SEED_PATH, false);
            assertThat(seed.get().fingerprint()).isEqualTo(KeysetMasterSeed.fromHex(SEED).fingerprint());
        }
    }

    @Test
    @DisplayName("its policy: writes keys, cannot overwrite the seed or read another seed, no sys/")
    void policyIsLeastPrivilege() {
        try (ConfigurableApplicationContext ctx = start()) {
            final var kv = ctx.getBean(VaultTemplate.class).opsForVersionedKeyValue("cashu");

            kv.put("keys/mint/ks/1", Map.of("private_key", "aa"));
            assertThat(kv.get("keys/mint/ks/1").getData()).containsEntry("private_key", "aa");

            assertThatThrownBy(() -> kv.put(SEED_PATH, Versioned.create(Map.of("seed", "00".repeat(32)),
                    Versioned.Version.from(1))))
                    .as("overwrite the seed with the right CAS version").hasMessageContaining("403");
            assertThatThrownBy(() -> kv.put(SEED_PATH, Map.of("seed", "00".repeat(32))))
                    .as("write the seed without CAS").hasMessageContaining("403");
            assertThatThrownBy(() -> kv.get("seeds/other")).hasMessageContaining("403");
            assertThatThrownBy(() -> kv.delete("keys/mint/ks/1")).hasMessageContaining("403");
            assertThatThrownBy(() -> ctx.getBean(VaultTemplate.class).opsForSys().getMounts())
                    .hasMessageContaining("403");
        }
    }
}
