package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort.MintLifecycleCommand;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.ActorDto;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.MintMetadataDto;
import xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.admin.rest.presenter.LifecycleSummaryApiPresenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleParityIntegrationTest {

    private static final String MINT_ID = "mint-parity";
    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String REQUEST_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final ActorDto ACTOR = new ActorDto(OPERATOR_ID, "Ops");
    private static final MintMetadataDto METADATA = new MintMetadataDto("Parity Mint", "Integration test mint", List.of("test"));

    private LifecycleSummaryPresenter presenter;
    private StubMintLifecyclePort cliPort;
    private AdminLifecycleService restService;

    @BeforeEach
    void setUp() {
        presenter = new LifecycleSummaryPresenter();
        resetAdapters();
    }

    @AfterEach
    void tearDown() {
        CorrelationIdContext.clear();
    }

    @Test
    // Ensures mint creation responses are identical between CLI and REST adapters.
    void shouldAlignCreateSummariesBetweenCliAndRest() {
        final LifecycleSummary cliSummary = cliPort.execute(command(LifecycleAction.CREATE, "v1", "v1"));
        final LifecycleActionResponse restResponse = restService.createMint(createRequest("v1"));

        assertParity(cliSummary, restResponse);
    }

    @Test
    // Ensures lifecycle updates report the same outcome between CLI and REST flows.
    void shouldAlignUpdateSummariesBetweenCliAndRest() {
        seedMint("v1");

        final LifecycleSummary cliSummary = cliPort.execute(command(LifecycleAction.UPDATE, "v2", "v2"));
        final LifecycleActionResponse restResponse = restService.updateMint(MINT_ID, updateRequest("v2", "rev-2"));

        assertParity(cliSummary, restResponse);
    }

    @Test
    // Ensures pause transitions expose equivalent summaries through CLI and REST.
    void shouldAlignPauseSummariesBetweenCliAndRest() {
        seedMint("v1");

        final LifecycleSummary cliSummary = cliPort.execute(command(LifecycleAction.PAUSE, "pause-tag", "pause-tag"));
        final LifecycleActionResponse restResponse = restService.pauseMint(MINT_ID,
            changeRequest("maintenance", "pause-tag"));

        assertParity(cliSummary, restResponse);
    }

    @Test
    // Ensures resume transitions remain in sync between CLI and REST adapters.
    void shouldAlignResumeSummariesBetweenCliAndRest() {
        seedMint("v1");
        cliPort.execute(command(LifecycleAction.PAUSE, "pause-tag", "pause-tag"));
        restService.pauseMint(MINT_ID, changeRequest("maintenance", "pause-tag"));

        final LifecycleSummary cliSummary = cliPort.execute(command(LifecycleAction.RESUME, "resume-tag", "resume-tag"));
        final LifecycleActionResponse restResponse = restService.resumeMint(MINT_ID,
            changeRequest("resumption", "resume-tag"));

        assertParity(cliSummary, restResponse);
    }

    @Test
    // Ensures retire transitions publish matching summaries across adapters.
    void shouldAlignRetireSummariesBetweenCliAndRest() {
        seedMint("v1");
        cliPort.execute(command(LifecycleAction.PAUSE, "pause-tag", "pause-tag"));
        restService.pauseMint(MINT_ID, changeRequest("maintenance", "pause-tag"));

        final LifecycleSummary cliSummary = cliPort.execute(command(LifecycleAction.RETIRE, "retire-tag", "retire-tag"));
        final LifecycleActionResponse restResponse = restService.retireMint(MINT_ID,
            changeRequest("retire", "retire-tag"));

        assertParity(cliSummary, restResponse);
    }

    @Test
    // Ensures update failures for missing mints are consistent between CLI and REST adapters.
    void shouldAlignUpdateFailuresBetweenCliAndRest() {
        assertThatThrownBy(() -> cliPort.execute(command(LifecycleAction.UPDATE, "v2", "v2")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mint not found");

        assertThatThrownBy(() -> restService.updateMint(MINT_ID, updateRequest("v2", "rev-2")))
            .isInstanceOf(AdminServiceException.class)
            .hasMessageContaining("not found")
            .satisfies(ex -> assertThat(((AdminServiceException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    // Ensures pause failures for missing mints align between CLI and REST flows.
    void shouldAlignPauseFailuresBetweenCliAndRest() {
        assertThatThrownBy(() -> cliPort.execute(command(LifecycleAction.PAUSE, "pause-tag", "pause-tag")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mint not found");

        assertThatThrownBy(() -> restService.pauseMint(MINT_ID, changeRequest("maintenance", "pause-tag")))
            .isInstanceOf(AdminServiceException.class)
            .hasMessageContaining("not found")
            .satisfies(ex -> assertThat(((AdminServiceException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    // Ensures resume failures for missing mints align between CLI and REST flows.
    void shouldAlignResumeFailuresBetweenCliAndRest() {
        assertThatThrownBy(() -> cliPort.execute(command(LifecycleAction.RESUME, "resume-tag", "resume-tag")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mint not found");

        assertThatThrownBy(() -> restService.resumeMint(MINT_ID, changeRequest("resumption", "resume-tag")))
            .isInstanceOf(AdminServiceException.class)
            .hasMessageContaining("not found")
            .satisfies(ex -> assertThat(((AdminServiceException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    // Ensures retire failures for missing mints align between CLI and REST flows.
    void shouldAlignRetireFailuresBetweenCliAndRest() {
        assertThatThrownBy(() -> cliPort.execute(command(LifecycleAction.RETIRE, "retire-tag", "retire-tag")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mint not found");

        assertThatThrownBy(() -> restService.retireMint(MINT_ID, changeRequest("retire", "retire-tag")))
            .isInstanceOf(AdminServiceException.class)
            .hasMessageContaining("not found")
            .satisfies(ex -> assertThat(((AdminServiceException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void seedMint(final String versionTag) {
        resetAdapters();
        cliPort.execute(command(LifecycleAction.CREATE, versionTag, versionTag));
        restService.createMint(createRequest(versionTag));
    }

    private void resetAdapters() {
        cliPort = new StubMintLifecyclePort(presenter);
        restService = new AdminLifecycleService(new InMemoryMintLifecycleUseCase(), new EmptyMintRepository(), presenter, new LifecycleSummaryApiPresenter());
    }

    private MintLifecycleCommand command(final LifecycleAction action, final String versionTag, final String correlationId) {
        return new MintLifecycleCommand(action, lifecycleRequest(versionTag, correlationId));
    }

    private MintLifecycleRequest lifecycleRequest(final String versionTag, final String correlationId) {
        return new MintLifecycleRequest(MINT_ID, OPERATOR_ID, versionTag, REQUEST_ID, correlationId);
    }

    private CreateMintRequest createRequest(final String versionTag) {
        return new CreateMintRequest(MINT_ID, ACTOR, METADATA, Map.of("versionTag", versionTag));
    }

    private UpdateMintRequest updateRequest(final String versionTag, final String revisionId) {
        return new UpdateMintRequest(ACTOR, METADATA, Map.of("versionTag", versionTag), revisionId);
    }

    private LifecycleChangeRequest changeRequest(final String reason, final String correlationId) {
        return new LifecycleChangeRequest(ACTOR, reason, correlationId);
    }

    private void assertParity(final LifecycleSummary summary, final LifecycleActionResponse response) {
        assertThat(response.operation()).isEqualTo(summary.operation().name());
        assertThat(response.mintId()).isEqualTo(summary.mintId());
        assertThat(response.currentState()).isEqualTo(summary.currentState());
        assertThat(response.versionTag()).isEqualTo(summary.versionTag());
        assertThat(response.changed()).isEqualTo(summary.changed());
        assertThat(response.message()).isEqualTo(summary.message());
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
    }

    /**
     * Minimal in-memory implementation of the use case for testing CLI/REST parity.
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
                    mints.put(request.mintId(), LifecycleState.State.PROVISIONED);
                    versionTags.put(request.mintId(), request.versionTag());
                    yield new ManageMintLifecycleResponse(request.mintId(),
                        LifecycleState.State.PROVISIONED, request.versionTag());
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
