package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase.OperationalCommand;

class BaseExecuteOperationalControlsInteractorTest {

    private static final ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest VALID_REQUEST =
        new ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest("123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174001", OperationalCommand.SCHEDULE_MAINTENANCE, "v1");

    private final BaseExecuteOperationalControlsInteractor interactor = new BaseExecuteOperationalControlsInteractor();

    @Test
    // Ensures handle rejects null requests.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.handle(null));
    }

    @Test
    // Ensures handle validates the mint identifier.
    void shouldRejectInvalidMintId() {
        final ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest request =
            new ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest("invalid",
                "123e4567-e89b-12d3-a456-426614174001", OperationalCommand.SCHEDULE_MAINTENANCE, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the operator identifier.
    void shouldRejectInvalidOperatorId() {
        final ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest request =
            new ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest("123e4567-e89b-12d3-a456-426614174000",
                "not-a-uuid", OperationalCommand.SCHEDULE_MAINTENANCE, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the command presence.
    void shouldRejectNullCommand() {
        final ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest request =
            new ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", null, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the version tag.
    void shouldRejectBlankVersionTag() {
        final ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest request =
            new ExecuteOperationalControlsUseCase.ExecuteOperationalControlsRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", OperationalCommand.SCHEDULE_MAINTENANCE, " ");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures a valid request reaches the unsupported branch.
    void shouldReachUnsupportedOperationForValidRequest() {
        assertThrows(UnsupportedOperationException.class, () -> interactor.handle(VALID_REQUEST));
    }
}
