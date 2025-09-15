package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.OperationalCommand;

class BaseExecuteOperationalControlsInteractorTest {

    private BaseExecuteOperationalControlsInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new BaseExecuteOperationalControlsInteractor();
    }

    // Ensures malformed mint identifiers trigger validation failures.
    @Test
    void shouldRejectInvalidMintId() {
        final ExecuteOperationalControlsRequest request = new ExecuteOperationalControlsRequest("invalid",
            validOperatorId(),
            OperationalCommand.ROTATE_KEYS,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mint identifier must be a valid UUID");
    }

    // Ensures malformed operator identifiers are rejected early.
    @Test
    void shouldRejectInvalidOperatorId() {
        final ExecuteOperationalControlsRequest request = new ExecuteOperationalControlsRequest(validMintId(),
            "not-a-uuid",
            OperationalCommand.ROTATE_KEYS,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operator id must be a valid UUID");
    }

    // Ensures null operational commands are rejected during validation.
    @Test
    void shouldRejectNullOperationalCommand() {
        final ExecuteOperationalControlsRequest request = new ExecuteOperationalControlsRequest(validMintId(),
            validOperatorId(),
            null,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operational command must not be null");
    }

    // Ensures blank version tags fail fast before hitting the unsupported stub.
    @Test
    void shouldRejectBlankVersionTag() {
        final ExecuteOperationalControlsRequest request = new ExecuteOperationalControlsRequest(validMintId(),
            validOperatorId(),
            OperationalCommand.ROTATE_KEYS,
            " ");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version tag must not be blank");
    }

    // Ensures valid payloads pass validation and raise the unsupported stub exception.
    @Test
    void shouldThrowUnsupportedAfterValidation() {
        final ExecuteOperationalControlsRequest request = new ExecuteOperationalControlsRequest(validMintId(),
            validOperatorId(),
            OperationalCommand.ROTATE_KEYS,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("ExecuteOperationalControlsUseCase has not been implemented yet");
    }

    private String validMintId() {
        return UUID.randomUUID().toString();
    }

    private String validOperatorId() {
        return UUID.randomUUID().toString();
    }
}
