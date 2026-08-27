package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AccessCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessAuditRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessAuditRepository.OperatorAccessAuditEntry;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;

class AdministerAccessInteractorTest {

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String USER_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final String PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private static final Instant NOW = Instant.parse("2026-08-27T10:15:30Z");

    private InMemoryOperatorAccessRepository repository;
    private InMemoryAuditRepository auditRepository;
    private AdministerAccessInteractor interactor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOperatorAccessRepository();
        auditRepository = new InMemoryAuditRepository();
        interactor = new AdministerAccessInteractor(repository, auditRepository,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // Verifies provisioning stores a new operator account and returns an active response.
    @Test
    void shouldProvisionOperatorAccount() {
        final AdministerAccessResponse response = interactor.handle(provision(PUBKEY));

        assertThat(response.targetAccountId()).isEqualTo(USER_ID);
        assertThat(response.active()).isTrue();
        assertThat(response.message()).isEqualTo("User created");
        assertThat(repository.findById(USER_ID)).isPresent();
    }

    // Ensures duplicate provisioning requests are rejected with a conflict-style domain error.
    @Test
    void shouldRejectDuplicateProvision() {
        interactor.handle(provision(PUBKEY));

        assertThatThrownBy(() -> interactor.handle(provision(PUBKEY)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already exists");
    }

    // Checks the public key survives provisioning: it is now the only way the operator signs in.
    @Test
    void shouldStoreThePublicKeyProvisioningSupplied() {
        interactor.handle(provision(PUBKEY));

        assertThat(repository.findByPubkey(PUBKEY)).isPresent();
    }

    // Checks a role change leaves the public key alone, so an update cannot lock an operator out.
    @Test
    void shouldKeepThePublicKeyAcrossRoleUpdates() {
        interactor.handle(provision(PUBKEY));

        interactor.handle(new AdministerAccessRequest(OPERATOR_ID, USER_ID,
            AccessCommand.UPDATE_ROLES, "v1", null, null, Set.of("OPS_ADMIN"), null, "rotation"));

        assertThat(repository.findByPubkey(PUBKEY)).isPresent();
    }

    // Checks an operator without a public key is refused: nobody could ever sign in as that row.
    @Test
    void shouldRejectProvisioningWithoutAPublicKey() {
        assertThatThrownBy(() -> interactor.handle(provision(null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pubkey");
    }

    // Checks the role that outranks every other cannot be written through the use case:
    // it is named in configuration, so an operator who may edit roles must not be able to
    // grant it to themselves.
    @Test
    void shouldRejectGrantingTheSuperAdministratorRole() {
        interactor.handle(provision(PUBKEY));

        assertThatThrownBy(() -> interactor.handle(new AdministerAccessRequest(OPERATOR_ID, USER_ID,
            AccessCommand.UPDATE_ROLES, "v1", "Alice", "alice@example.com",
            Set.of("SUPER_ADMIN"), null, "self-promotion")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SUPER_ADMIN");
    }

    // Checks a role string outside the build's vocabulary is refused rather than stored:
    // the resolver grants nothing for a role it does not recognise, so persisting one
    // writes an entitlement that reads as granted and is not.
    @Test
    void shouldRejectAnUnknownRole() {
        interactor.handle(provision(PUBKEY));

        assertThatThrownBy(() -> interactor.handle(new AdministerAccessRequest(OPERATOR_ID, USER_ID,
            AccessCommand.UPDATE_ROLES, "v1", "Alice", "alice@example.com",
            Set.of("ANALYST"), null, "typo")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ANALYST");
    }

    // Checks suspension leaves the row in place: an operator named in the audit trail has to
    // stay resolvable, and reinstating them must not require re-provisioning.
    @Test
    void shouldSuspendWithoutDeletingAndThenReinstate() {
        interactor.handle(provision(PUBKEY));

        assertThat(interactor.handle(lifecycle(AccessCommand.REVOKE)).active()).isFalse();
        assertThat(repository.findById(USER_ID)).isPresent();
        assertThat(repository.findById(USER_ID).orElseThrow().roles()).containsExactly("MINT_ADMIN");

        assertThat(interactor.handle(lifecycle(AccessCommand.REINSTATE)).active()).isTrue();
    }

    // Checks every management action lands in the audit trail naming the operator who acted,
    // so a role grant or suspension can be traced back to a person rather than to the service.
    @Test
    void shouldRecordEveryActionAgainstTheActingOperator() {
        interactor.handle(provision(PUBKEY));
        interactor.handle(lifecycle(AccessCommand.REVOKE));

        assertThat(auditRepository.findAll()).extracting(
                OperatorAccessAuditEntry::actor,
                OperatorAccessAuditEntry::action,
                OperatorAccessAuditEntry::targetAccountId,
                OperatorAccessAuditEntry::occurredAt)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(OPERATOR_ID, "PROVISION", USER_ID, NOW),
                org.assertj.core.groups.Tuple.tuple(OPERATOR_ID, "REVOKE", USER_ID, NOW));
    }

    // Checks a failed action leaves no audit entry: the trail records what happened, and a
    // rejected request that appeared in it would read as a change that was never made.
    @Test
    void shouldNotRecordARejectedAction() {
        assertThatThrownBy(() -> interactor.handle(lifecycle(AccessCommand.REVOKE)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(auditRepository.findAll()).isEmpty();
    }

    private static AdministerAccessRequest lifecycle(final AccessCommand command) {
        return new AdministerAccessRequest(OPERATOR_ID, USER_ID, command, "v1",
            null, null, Set.of(), null, "offboarding");
    }

    private static AdministerAccessRequest provision(final String pubkey) {
        return new AdministerAccessRequest(OPERATOR_ID, USER_ID, AccessCommand.PROVISION, "v1",
            "Alice", "alice@example.com", Set.of("MINT_ADMIN"), pubkey, null);
    }

    private static final class InMemoryAuditRepository implements OperatorAccessAuditRepository {

        private final List<OperatorAccessAuditEntry> entries = new ArrayList<>();

        @Override
        public void record(final OperatorAccessAuditEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<OperatorAccessAuditEntry> findAll() {
            return List.copyOf(entries);
        }
    }

    private static final class InMemoryOperatorAccessRepository implements OperatorAccessRepository {

        private final Map<String, OperatorAccessAccount> accounts = new ConcurrentHashMap<>();

        @Override
        public boolean create(final OperatorAccessAccount account) {
            return accounts.putIfAbsent(account.accountId(), account) == null;
        }

        @Override
        public Optional<OperatorAccessAccount> findById(final String accountId) {
            return Optional.ofNullable(accounts.get(accountId));
        }

        @Override
        public void update(final OperatorAccessAccount account) {
            accounts.put(account.accountId(), account);
        }

        @Override
        public List<OperatorAccessAccount> findAll() {
            return List.copyOf(accounts.values());
        }

        @Override
        public Optional<OperatorAccessAccount> findByPubkey(final String pubkey) {
            return accounts.values().stream()
                .filter(a -> pubkey != null && pubkey.equals(a.pubkey()))
                .findFirst();
        }
    }
}
