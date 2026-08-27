package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;

class AdministerAccessInteractorTest {

    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String USER_ID = "123e4567-e89b-12d3-a456-426614174001";
    private static final String PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private InMemoryOperatorAccessRepository repository;
    private AdministerAccessInteractor interactor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOperatorAccessRepository();
        interactor = new AdministerAccessInteractor(repository);
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

    private static AdministerAccessRequest provision(final String pubkey) {
        return new AdministerAccessRequest(OPERATOR_ID, USER_ID, AccessCommand.PROVISION, "v1",
            "Alice", "alice@example.com", Set.of("MINT_ADMIN"), pubkey, null);
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
