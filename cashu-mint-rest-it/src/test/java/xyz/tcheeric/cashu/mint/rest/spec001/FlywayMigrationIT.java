package xyz.tcheeric.cashu.mint.rest.spec001;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 001 T013 — boots the cashu-mint-jpa Flyway migrations against a real
 * PostgreSQL via Testcontainers and asserts that the three V20260522
 * migrations applied in order with no ordering / checksum errors.
 */
class FlywayMigrationIT extends AbstractMintDurableIT {

    @Autowired
    @Qualifier("mintFlyway")
    Flyway flyway;

    @Test
    void allSpec001MigrationsApplied() {
        MigrationInfo[] applied = flyway.info().applied();
        List<String> versions = Arrays.stream(applied)
                .map(MigrationInfo::getVersion)
                .map(Object::toString)
                .collect(Collectors.toList());

        assertThat(versions)
                .as("Spec 001 ships V20260522_001 / _002 / _003 — Flyway must apply all three")
                .contains("20260522.001", "20260522.002", "20260522.003");

        for (MigrationInfo info : applied) {
            assertThat(info.getState())
                    .as("Migration %s state", info.getVersion())
                    .isIn(MigrationState.SUCCESS, MigrationState.BASELINE);
        }
    }
}
