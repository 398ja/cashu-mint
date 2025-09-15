package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.HealthQuery;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase.MonitorMintHealthRequest;

class BaseMonitorMintHealthInteractorTest {

    private BaseMonitorMintHealthInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new BaseMonitorMintHealthInteractor();
    }

    // Ensures a malformed mint identifier is rejected during validation.
    @Test
    void shouldRejectInvalidMintId() {
        final MonitorMintHealthRequest request = new MonitorMintHealthRequest("invalid",
            HealthQuery.SNAPSHOT,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mint identifier must be a valid UUID");
    }

    // Ensures null health queries are rejected as invalid input.
    @Test
    void shouldRejectNullHealthQuery() {
        final MonitorMintHealthRequest request = new MonitorMintHealthRequest(validMintId(),
            null,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("health query must not be null");
    }

    // Ensures blank version tags fail fast during validation.
    @Test
    void shouldRejectBlankVersionTag() {
        final MonitorMintHealthRequest request = new MonitorMintHealthRequest(validMintId(),
            HealthQuery.SNAPSHOT,
            " ");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version tag must not be blank");
    }

    // Ensures valid payloads raise the unsupported stub exception after validation succeeds.
    @Test
    void shouldThrowUnsupportedAfterValidation() {
        final MonitorMintHealthRequest request = new MonitorMintHealthRequest(validMintId(),
            HealthQuery.SNAPSHOT,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("MonitorMintHealthUseCase has not been implemented yet");
    }

    private String validMintId() {
        return UUID.randomUUID().toString();
    }
}
