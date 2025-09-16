package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintLifecyclePort.MintLifecycleCommand;
import xyz.tcheeric.cashu.mint.admin.cli.port.stub.StubMintLifecyclePort;
import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleAction;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummary;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.MintMetadataDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.presenter.LifecycleSummaryApiPresenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleParityIntegrationTest {

    private static final String MINT_ID = "mint-parity";
    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String REQUEST_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final ActorDto ACTOR = new ActorDto("ops", "Ops");
    private static final MintMetadataDto METADATA = new MintMetadataDto("Parity Mint", "Integration test mint", List.of("test"));

    private LifecycleSummaryPresenter presenter;
    private StubMintLifecyclePort cliPort;
    private AdminLifecycleService restService;

    @BeforeEach
    void setUp() {
        presenter = new LifecycleSummaryPresenter();
        cliPort = new StubMintLifecyclePort(presenter);
        restService = new AdminLifecycleService(new ConcurrentHashMap<>(), presenter, new LifecycleSummaryApiPresenter());
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
    // Ensures repeated create requests surface matching idempotent summaries.
    void shouldAlignCreateIdempotentSummariesBetweenCliAndRest() {
        seedMint("v1");

        final LifecycleSummary cliSummary = cliPort.execute(command(LifecycleAction.CREATE, "v2", "v2"));
        final LifecycleActionResponse restResponse = restService.createMint(createRequest("v2"));

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
            .hasMessageContaining("Mint not found: " + MINT_ID)
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
            .hasMessageContaining("Mint not found: " + MINT_ID)
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
            .hasMessageContaining("Mint not found: " + MINT_ID)
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
            .hasMessageContaining("Mint not found: " + MINT_ID)
            .satisfies(ex -> assertThat(((AdminServiceException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void seedMint(final String versionTag) {
        cliPort.execute(command(LifecycleAction.CREATE, versionTag, versionTag));
        restService.createMint(createRequest(versionTag));
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
        assertThat(response.previousState()).isEqualTo(summary.previousState());
        assertThat(response.currentState()).isEqualTo(summary.currentState());
        assertThat(response.versionTag()).isEqualTo(summary.versionTag());
        assertThat(response.changed()).isEqualTo(summary.changed());
        assertThat(response.message()).isEqualTo(summary.message());
    }
}
