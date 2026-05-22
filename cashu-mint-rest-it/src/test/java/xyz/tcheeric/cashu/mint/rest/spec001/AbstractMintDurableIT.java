package xyz.tcheeric.cashu.mint.rest.spec001;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
@Testcontainers
public abstract class AbstractMintDurableIT {

    @Container
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cashu_mint_it")
                    .withUsername("cashu")
                    .withPassword("cashu");

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
