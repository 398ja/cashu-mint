package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.MintMetadataDto;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.admin.rest.presenter.LifecycleSummaryApiPresenter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLifecycleServiceTest {

    private static final String MINT_ID = "mint-ctx";
    private static final String OPERATOR_UUID = UUID.randomUUID().toString();
    private static final MintMetadataDto METADATA = new MintMetadataDto("Mint", "Primary mint", List.of("prod"));

    private AdminLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new AdminLifecycleService(new InMemoryMintLifecycleUseCase(),
            new EmptyMintRepository(),
            new LifecycleSummaryPresenter(),
            new LifecycleSummaryApiPresenter(),
            fixedOperatorIdentity(),
            mintId -> List.of());
    }

    // The filter chain does not run in this test, so stand in for the operator it
    // would otherwise have resolved onto the request.
    private static OperatorIdentity fixedOperatorIdentity() {
        return new OperatorIdentity(org.mockito.Mockito.mock(OperatorAccessRepository.class)) {
            @Override
            public String currentOperatorId() {
                return OPERATOR_UUID;
            }
        };
    }

    @AfterEach
    void tearDown() {
        CorrelationIdContext.clear();
    }

    @Test
    // Ensures mint provisioning mirrors the CLI success path by returning a changed summary.
    void shouldCreateMintWhenNotExisting() {
        final LifecycleActionResponse response = service.createMint(createRequest(MINT_ID, "v1"));

        assertThat(response.operation()).isEqualTo("CREATE");
        assertThat(response.currentState()).isEqualTo("PROVISIONING");
        assertThat(response.changed()).isTrue();
        assertThat(response.message()).isEqualTo("Mint created");
    }

    @Test
    // Ensures repeated provisioning requests surface a conflict error.
    void shouldRejectCreateWhenMintAlreadyExists() {
        service.createMint(createRequest(MINT_ID, "v1"));

        assertThatThrownBy(() -> service.createMint(createRequest(MINT_ID, "v2")))
            .isInstanceOf(AdminServiceException.class)
            .satisfies(ex -> {
                final AdminServiceException failure = (AdminServiceException) ex;
                assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(failure.getCode()).isEqualTo("mint_already_exists");
            });
    }

    @Test
    // Ensures lifecycle updates apply new configuration details and mark the response as changed.
    void shouldUpdateMintWhenExisting() {
        service.createMint(createRequest(MINT_ID, "v1"));

        final LifecycleActionResponse response = service.updateMint(MINT_ID,
            updateRequest("v2", "rev-2"));

        assertThat(response.operation()).isEqualTo("UPDATE");
        assertThat(response.changed()).isTrue();
        assertThat(response.versionTag()).isEqualTo("v2");
        assertThat(response.message()).isEqualTo("Mint updated");
    }

    @Test
    // Ensures lifecycle updates reject requests when the target mint is missing.
    void shouldRejectUpdateWhenMintMissing() {
        assertThatThrownBy(() -> service.updateMint("missing", updateRequest("v2", "rev-2")))
            .isInstanceOf(AdminServiceException.class)
            .satisfies(ex -> {
                final AdminServiceException failure = (AdminServiceException) ex;
                assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                assertThat(failure.getCode()).isEqualTo("mint_not_found");
            });
    }

    @Test
    // Ensures pausing a mint records a suspended state and returns the CLI-equivalent summary.
    void shouldPauseMintWhenExisting() {
        service.createMint(createRequest(MINT_ID, "v1"));

        final LifecycleActionResponse response = service.pauseMint(MINT_ID,
            changeRequest("maintenance", "pause-tag"));

        assertThat(response.operation()).isEqualTo("PAUSE");
        assertThat(response.currentState()).isEqualTo("SUSPENDED");
        assertThat(response.message()).isEqualTo("Mint paused");
    }

    @Test
    // Ensures lifecycle pause requests fail when the mint aggregate has not been created.
    void shouldRejectPauseWhenMintMissing() {
        assertThatThrownBy(() -> service.pauseMint("missing", changeRequest("maintenance", "pause-tag")))
            .isInstanceOf(AdminServiceException.class)
            .satisfies(ex -> assertMissingMintFailure((AdminServiceException) ex));
    }

    @Test
    // Ensures resuming a suspended mint yields an ACTIVE lifecycle summary.
    void shouldResumeMintWhenSuspended() {
        service.createMint(createRequest(MINT_ID, "v1"));
        service.pauseMint(MINT_ID, changeRequest("maintenance", "pause-tag"));

        final LifecycleActionResponse response = service.resumeMint(MINT_ID,
            changeRequest("resume", "resume-tag"));

        assertThat(response.operation()).isEqualTo("RESUME");
        assertThat(response.currentState()).isEqualTo("ACTIVE");
        assertThat(response.message()).isEqualTo("Mint resumed");
    }

    @Test
    // Ensures lifecycle resume requests fail when the mint aggregate is missing.
    void shouldRejectResumeWhenMintMissing() {
        assertThatThrownBy(() -> service.resumeMint("missing", changeRequest("resume", "resume-tag")))
            .isInstanceOf(AdminServiceException.class)
            .satisfies(ex -> assertMissingMintFailure((AdminServiceException) ex));
    }

    @Test
    // Ensures retiring a mint transitions it to the decommissioned state with CLI-equivalent messaging.
    void shouldRetireMintWhenExisting() {
        service.createMint(createRequest(MINT_ID, "v1"));
        service.pauseMint(MINT_ID, changeRequest("maintenance", "pause-tag"));

        final LifecycleActionResponse response = service.retireMint(MINT_ID,
            changeRequest("retire", "retire-tag"));

        assertThat(response.operation()).isEqualTo("RETIRE");
        assertThat(response.currentState()).isEqualTo("DECOMMISSIONED");
        assertThat(response.message()).isEqualTo("Mint retired");
    }

    @Test
    // Ensures lifecycle retire requests fail when the mint aggregate is missing.
    void shouldRejectRetireWhenMintMissing() {
        assertThatThrownBy(() -> service.retireMint("missing", changeRequest("retire", "retire-tag")))
            .isInstanceOf(AdminServiceException.class)
            .satisfies(ex -> assertMissingMintFailure((AdminServiceException) ex));
    }

    private CreateMintRequest createRequest(final String mintId, final String versionTag) {
        return new CreateMintRequest(mintId, METADATA, Map.of("versionTag", versionTag));
    }

    private UpdateMintRequest updateRequest(final String versionTag, final String revisionId) {
        return new UpdateMintRequest(METADATA, Map.of("versionTag", versionTag, "maxPeers", 5), revisionId);
    }

    private LifecycleChangeRequest changeRequest(final String reason, final String correlationId) {
        return new LifecycleChangeRequest(reason, correlationId);
    }

    private void assertMissingMintFailure(final AdminServiceException failure) {
        assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(failure.getCode()).isEqualTo("mint_not_found");
    }

    private static final class EmptyMintRepository implements MintRepository {
        @Override
        public void save(final MintAggregate aggregate) { }

        @Override
        public Optional<MintAggregate> findById(final MintId mintId) {
            return Optional.empty();
        }

        @Override
        public List<MintAggregate> findAll() {
            return List.of();
        }

        @Override
        public boolean existsActiveByUnit(final String unit, final MintId excludeMintId) {
            return false;
        }
    }

    /**
     * Minimal in-memory implementation of the use case for unit testing the service layer.
     */
    private static final class InMemoryMintLifecycleUseCase implements ManageMintLifecycleUseCase {

        private final Map<String, LifecycleState.State> mints = new ConcurrentHashMap<>();
        private final Map<String, String> versionTags = new ConcurrentHashMap<>();

        @Override
        public ManageMintLifecycleResponse handle(final ManageMintLifecycleRequest request) {
            return switch (request.command()) {
                case CREATE -> {
                    if (mints.containsKey(request.mintId())) {
                        throw new IllegalStateException("mint already exists: " + request.mintId());
                    }
                    mints.put(request.mintId(), LifecycleState.State.PROVISIONING);
                    versionTags.put(request.mintId(), request.versionTag());
                    yield new ManageMintLifecycleResponse(request.mintId(),
                        LifecycleState.State.PROVISIONING, request.versionTag());
                }
                case UPDATE_CONFIGURATION -> {
                    requireExisting(request.mintId());
                    versionTags.put(request.mintId(), request.versionTag());
                    yield new ManageMintLifecycleResponse(request.mintId(),
                        mints.get(request.mintId()), request.versionTag());
                }
                case PAUSE -> transition(request, LifecycleState.State.SUSPENDED);
                case RESUME -> transition(request, LifecycleState.State.ACTIVE);
                case RETIRE -> transition(request, LifecycleState.State.DECOMMISSIONED);
            };
        }

        private ManageMintLifecycleResponse transition(final ManageMintLifecycleRequest request,
                                                        final LifecycleState.State target) {
            requireExisting(request.mintId());
            mints.put(request.mintId(), target);
            versionTags.put(request.mintId(), request.versionTag());
            return new ManageMintLifecycleResponse(request.mintId(), target, request.versionTag());
        }

        private void requireExisting(final String mintId) {
            if (!mints.containsKey(mintId)) {
                throw new IllegalStateException("mint not found: " + mintId);
            }
        }
    }
}
