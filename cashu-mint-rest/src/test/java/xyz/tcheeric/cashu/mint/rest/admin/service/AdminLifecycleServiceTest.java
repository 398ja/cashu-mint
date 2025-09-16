package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.MintMetadataDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.presenter.LifecycleSummaryApiPresenter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLifecycleServiceTest {

    private static final String MINT_ID = "mint-ctx";
    private static final ActorDto ACTOR = new ActorDto("ops", "Ops");
    private static final MintMetadataDto METADATA = new MintMetadataDto("Mint", "Primary mint", List.of("prod"));

    private AdminLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new AdminLifecycleService(new ConcurrentHashMap<>(),
            new LifecycleSummaryPresenter(),
            new LifecycleSummaryApiPresenter());
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
        assertThat(response.currentState()).isEqualTo("PROVISIONED");
        assertThat(response.changed()).isTrue();
        assertThat(response.message()).isEqualTo("Mint created");
    }

    @Test
    // Ensures repeated provisioning requests surface an idempotent lifecycle summary.
    void shouldReturnIdempotentSummaryWhenMintAlreadyExists() {
        service.createMint(createRequest(MINT_ID, "v1"));

        final LifecycleActionResponse response = service.createMint(createRequest(MINT_ID, "v2"));

        assertThat(response.changed()).isFalse();
        assertThat(response.versionTag()).isEqualTo("v1");
        assertThat(response.message()).isEqualTo("Mint already exists");
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
        assertThat(response.previousState()).isEqualTo("PROVISIONED");
        assertThat(response.currentState()).isEqualTo("SUSPENDED");
        assertThat(response.versionTag()).isEqualTo("pause-tag");
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
        assertThat(response.previousState()).isEqualTo("SUSPENDED");
        assertThat(response.currentState()).isEqualTo("ACTIVE");
        assertThat(response.versionTag()).isEqualTo("resume-tag");
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
        assertThat(response.previousState()).isEqualTo("SUSPENDED");
        assertThat(response.currentState()).isEqualTo("DECOMMISSIONED");
        assertThat(response.versionTag()).isEqualTo("retire-tag");
        assertThat(response.message()).isEqualTo("Mint retired");
    }

    @Test
    // Ensures lifecycle retire requests fail when the mint aggregate is missing.
    void shouldRejectRetireWhenMintMissing() {
        assertThatThrownBy(() -> service.retireMint("missing", changeRequest("retire", "retire-tag")))
            .isInstanceOf(AdminServiceException.class)
            .satisfies(ex -> assertMissingMintFailure((AdminServiceException) ex));
    }

    // Ensures lifecycle transitions adopt the correlation identifier from the request context.
    @Test
    void shouldUseContextCorrelationIdWhenRequestOmitsValue() {
        service.createMint(createRequest(MINT_ID, "v1"));

        CorrelationIdContext.init("rest-generated-id");

        final LifecycleActionResponse response = service.pauseMint(MINT_ID, new LifecycleChangeRequest(
                ACTOR,
                "maintenance",
                null
        ));

        assertThat(response.versionTag()).isEqualTo("rest-generated-id");
    }

    private CreateMintRequest createRequest(final String mintId, final String versionTag) {
        return new CreateMintRequest(mintId, ACTOR, METADATA, Map.of("versionTag", versionTag));
    }

    private UpdateMintRequest updateRequest(final String versionTag, final String revisionId) {
        return new UpdateMintRequest(ACTOR, METADATA, Map.of("versionTag", versionTag, "maxPeers", 5), revisionId);
    }

    private LifecycleChangeRequest changeRequest(final String reason, final String correlationId) {
        return new LifecycleChangeRequest(ACTOR, reason, correlationId);
    }

    private void assertMissingMintFailure(final AdminServiceException failure) {
        assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(failure.getCode()).isEqualTo("mint_not_found");
    }
}
