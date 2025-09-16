package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OperatorAccountTest {

    private static AuditMetadata metadata() {
        return new AuditMetadata("operator", "create-operator", Instant.now());
    }

    @Test
    // Ensures constructor captures identifiers, names, and roles.
    void shouldCreateOperatorAccount() {
        final UUID operatorId = UUID.fromString("123e4567-e89b-12d3-a456-426614174100");
        final OperatorAccount account = new OperatorAccount(operatorId, "Alice", Set.of("ADMIN"), metadata());

        assertThat(account.operatorId()).isEqualTo(operatorId);
        assertThat(account.displayName()).isEqualTo("Alice");
        assertThat(account.roles()).containsExactly("ADMIN");
    }

    @Test
    // Ensures display names must not be blank.
    void shouldThrowWhenDisplayNameBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> new OperatorAccount(UUID.randomUUID(), "", Set.of("ADMIN"), metadata()));
    }

    @Test
    // Ensures roles cannot contain blank entries.
    void shouldThrowWhenRolesContainBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> new OperatorAccount(UUID.randomUUID(), "Alice", Set.of("ADMIN", " "), metadata()));
    }

    @Test
    // Ensures rename replaces the display name and audit metadata.
    void shouldRenameOperatorAccount() {
        final OperatorAccount account = new OperatorAccount(UUID.randomUUID(), "Alice", Set.of("ADMIN"), metadata());
        final AuditMetadata renameMetadata = new AuditMetadata("operator", "rename", Instant.now());

        final OperatorAccount renamed = account.rename("Bob", renameMetadata);

        assertThat(renamed.displayName()).isEqualTo("Bob");
        assertThat(renamed.auditMetadata()).isEqualTo(renameMetadata);
    }

    @Test
    // Ensures updateRoles replaces the roles set and audit metadata.
    void shouldUpdateRoles() {
        final OperatorAccount account = new OperatorAccount(UUID.randomUUID(), "Alice", Set.of("ADMIN"), metadata());
        final AuditMetadata updateMetadata = new AuditMetadata("operator", "roles", Instant.now());

        final OperatorAccount updated = account.updateRoles(Set.of("AUDITOR"), updateMetadata);

        assertThat(updated.roles()).containsExactly("AUDITOR");
        assertThat(updated.auditMetadata()).isEqualTo(updateMetadata);
    }
}
