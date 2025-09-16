package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.HealthQuery;

class BaseMonitorMintHealthInteractorTest {

    private static final MonitorMintHealthUseCase.MonitorMintHealthRequest VALID_REQUEST =
        new MonitorMintHealthUseCase.MonitorMintHealthRequest("123e4567-e89b-12d3-a456-426614174000",
            HealthQuery.SNAPSHOT, "v1");

    private final BaseMonitorMintHealthInteractor interactor = new BaseMonitorMintHealthInteractor();

    @Test
    // Ensures handle rejects null requests.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.handle(null));
    }

    @Test
    // Ensures handle validates the mint identifier.
    void shouldRejectInvalidMintId() {
        final MonitorMintHealthUseCase.MonitorMintHealthRequest request =
            new MonitorMintHealthUseCase.MonitorMintHealthRequest("invalid", HealthQuery.SNAPSHOT, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the query presence.
    void shouldRejectNullQuery() {
        final MonitorMintHealthUseCase.MonitorMintHealthRequest request =
            new MonitorMintHealthUseCase.MonitorMintHealthRequest("123e4567-e89b-12d3-a456-426614174000", null, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the version tag.
    void shouldRejectBlankVersionTag() {
        final MonitorMintHealthUseCase.MonitorMintHealthRequest request =
            new MonitorMintHealthUseCase.MonitorMintHealthRequest("123e4567-e89b-12d3-a456-426614174000",
                HealthQuery.SNAPSHOT, " ");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures a valid request reaches the unsupported branch.
    void shouldReachUnsupportedOperationForValidRequest() {
        assertThrows(UnsupportedOperationException.class, () -> interactor.handle(VALID_REQUEST));
    }
}
