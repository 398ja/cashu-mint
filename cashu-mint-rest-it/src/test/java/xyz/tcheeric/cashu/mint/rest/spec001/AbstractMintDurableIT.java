package xyz.tcheeric.cashu.mint.rest.spec001;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import xyz.tcheeric.cashu.mint.jpa.repository.IssuanceRecordJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication;

/**
 * Spec 001 — base class for integration tests that exercise the durable
 * mint quote / issuance / webhook tables under a real PostgreSQL via
 * Testcontainers.
 *
 * <p>The harness:
 * <ul>
 *   <li>Boots a shared {@link PostgreSQLContainer} per test class.</li>
 *   <li>Injects {@code cashu.mint.jpa.datasource.{url,username,password}}
 *       into the Spring context via {@link DynamicPropertySource} and flips
 *       {@code cashu.mint.jpa.enabled=true} so the spec-001 autoconfig
 *       activates.</li>
 *   <li>Runs Flyway migrations on first boot via {@code MintJpaAutoConfiguration}.</li>
 *   <li>Wipes the three durable tables in {@link #cleanDurableTables} before
 *       every test so cases are independent.</li>
 * </ul>
 *
 * <p>Subclasses inject the spec-001 repositories directly (and any other
 * Spring beans) via {@link Autowired}.
 */
@SpringBootTest(classes = CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "spec001-it"})
public abstract class AbstractMintDurableIT {

    /**
     * Singleton Postgres container shared by every spec-001 IT class. We don't
     * declare it with {@code @Container} because that scopes the lifecycle to
     * each test class — but Spring's {@code ApplicationContext} cache holds the
     * datasource URL across classes that share the same configuration, so the
     * second IT class would try to connect to a stopped container. The shared
     * singleton outlives every IT class in the JVM and is stopped by the JVM
     * shutdown hook Testcontainers installs (the Ryuk reaper container).
     */
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("cashu_mint_it")
                .withUsername("cashu")
                .withPassword("cashu")
                // Every cached Spring context holds its own Hikari pool
                // against this one container, so the connection budget grows
                // with the number of distinct IT context configurations. The
                // stock max_connections=100 is already close; raise it rather
                // than making each new IT contort its properties to reuse an
                // existing context.
                //
                // fsync=off is repeated because withCommand REPLACES the
                // command PostgreSQLContainer sets in its constructor rather
                // than appending to it; dropping it would quietly turn
                // durability back on and slow every durable IT down.
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("cashu.mint.jpa.enabled", () -> "true");
        registry.add("cashu.mint.jpa.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("cashu.mint.jpa.datasource.username", POSTGRES::getUsername);
        registry.add("cashu.mint.jpa.datasource.password", POSTGRES::getPassword);
        registry.add("cashu.mint.jpa.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("cashu.mint.jpa.flyway.enabled", () -> "true");

        // Spec 001 webhook contract: 'test' profile is non-local so the
        // startup validator runs. Supply a known shared secret + provider.
        registry.add("cashu.mint.webhook.shared-secret", () -> "it-shared-secret");
        registry.add("cashu.mint.webhook.provider", () -> "phoenixd-it");

        // Spec 004 FR-002 — HmacSha256IdentityHasher fail-closes when the
        // salt is unset OR shorter than 32 bytes. Set a deterministic
        // 64-char (32-byte) hex salt for ITs so the boot validator passes
        // and the converter produces stable hashes the assertions can
        // reproduce.
        registry.add("cashu.mint.voucher.identity-salt",
                () -> "9f8a2c1b7e4d6a3f0c5b9d8e7f6a4c2b1d8e9f7a3c5b2d4e6f1a8c0b9d7e5f3a");

        // Spec 004 V20260601_003 — Flyway placeholder for the
        // cashu_mint_grafana_ro role password (consumed by the
        // cashu-mint-jpa module's MintJpaProperties.flyway.placeholders).
        registry.add("cashu.mint.jpa.flyway.placeholders.grafana_ro_password",
                () -> "it-grafana-ro-password");
    }

    @Autowired
    protected MintQuoteJpaRepository mintQuoteJpaRepository;

    @Autowired
    protected IssuanceRecordJpaRepository issuanceRecordJpaRepository;

    @Autowired
    protected WebhookEventJpaRepository webhookEventJpaRepository;

    @BeforeEach
    void cleanDurableTables() {
        webhookEventJpaRepository.deleteAll();
        issuanceRecordJpaRepository.deleteAll();
        mintQuoteJpaRepository.deleteAll();
    }
}
