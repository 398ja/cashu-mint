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
        final AdministerAccessResponse response = interactor.handle(new AdministerAccessRequest(
            OPERATOR_ID,
            USER_ID,
            AccessCommand.PROVISION,
            "v1",
            "Alice",
            "alice@example.com",
            Set.of("ADMIN"),
            null));

        assertThat(response.targetAccountId()).isEqualTo(USER_ID);
        assertThat(response.active()).isTrue();
        assertThat(response.message()).isEqualTo("User created");
        assertThat(repository.findById(USER_ID)).isPresent();
    }

    // Ensures duplicate provisioning requests are rejected with a conflict-style domain error.
    @Test
    void shouldRejectDuplicateProvision() {
        interactor.handle(new AdministerAccessRequest(
            OPERATOR_ID,
            USER_ID,
            AccessCommand.PROVISION,
            "v1",
            "Alice",
            "alice@example.com",
            Set.of("ADMIN"),
            null));

        assertThatThrownBy(() -> interactor.handle(new AdministerAccessRequest(
            OPERATOR_ID,
            USER_ID,
            AccessCommand.PROVISION,
            "v1",
            "Alice",
            "alice@example.com",
            Set.of("ADMIN"),
            null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already exists");
    }

    // Confirms credential reset tokens are persisted and incremented across repeated resets.
    @Test
    void shouldIssueIncrementingResetTokens() {
        interactor.handle(new AdministerAccessRequest(
            OPERATOR_ID,
            USER_ID,
            AccessCommand.PROVISION,
            "v1",
            "Alice",
            "alice@example.com",
            Set.of("ADMIN"),
            null));

        final AdministerAccessResponse firstReset = interactor.handle(new AdministerAccessRequest(
            OPERATOR_ID,
            USER_ID,
            AccessCommand.RESET_CREDENTIALS,
            "v1"));
        final AdministerAccessResponse secondReset = interactor.handle(new AdministerAccessRequest(
            OPERATOR_ID,
            USER_ID,
            AccessCommand.RESET_CREDENTIALS,
            "v1"));

        // Credentials must be unpredictable, and must not be derivable from the account id.
        assertThat(firstReset.resetToken()).isNotBlank().doesNotContain(USER_ID);
        assertThat(secondReset.resetToken()).isNotBlank().isNotEqualTo(firstReset.resetToken());

        // Only the hash of the most recent credential is stored.
        // Provisioning issues the first credential, so two explicit resets make three.
        final OperatorAccessAccount stored = repository.findById(USER_ID).orElseThrow();
        assertThat(stored.credentialResetCount()).isEqualTo(3);
        assertThat(stored.credentialHash()).isEqualTo(OperatorCredentials.hash(secondReset.resetToken()));
        assertThat(stored.credentialHash()).isNotEqualTo(secondReset.resetToken());

        // A superseded credential no longer resolves.
        assertThat(repository.findByCredentialHash(OperatorCredentials.hash(firstReset.resetToken())))
            .isEmpty();
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

        @Override
        public Optional<OperatorAccessAccount> findByCredentialHash(final String credentialHash) {
            return accounts.values().stream()
                .filter(account -> credentialHash != null && credentialHash.equals(account.credentialHash()))
                .findFirst();
        }
    }
}
