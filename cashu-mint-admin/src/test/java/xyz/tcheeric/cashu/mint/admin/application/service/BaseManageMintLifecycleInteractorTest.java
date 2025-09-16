package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.LifecycleCommand;

class BaseManageMintLifecycleInteractorTest {

    private static final ManageMintLifecycleUseCase.ManageMintLifecycleRequest VALID_REQUEST =
        new ManageMintLifecycleUseCase.ManageMintLifecycleRequest("123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174001", LifecycleCommand.CREATE, "v1");

    private final BaseManageMintLifecycleInteractor interactor = new BaseManageMintLifecycleInteractor();

    @Test
    // Ensures handle rejects null requests.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.handle(null));
    }

    @Test
    // Ensures handle validates the mint identifier.
    void shouldRejectInvalidMintId() {
        final ManageMintLifecycleUseCase.ManageMintLifecycleRequest request =
            new ManageMintLifecycleUseCase.ManageMintLifecycleRequest("invalid",
                "123e4567-e89b-12d3-a456-426614174001", LifecycleCommand.CREATE, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the operator identifier.
    void shouldRejectInvalidOperatorId() {
        final ManageMintLifecycleUseCase.ManageMintLifecycleRequest request =
            new ManageMintLifecycleUseCase.ManageMintLifecycleRequest("123e4567-e89b-12d3-a456-426614174000",
                "not-a-uuid", LifecycleCommand.CREATE, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the command presence.
    void shouldRejectNullCommand() {
        final ManageMintLifecycleUseCase.ManageMintLifecycleRequest request =
            new ManageMintLifecycleUseCase.ManageMintLifecycleRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", null, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the version tag.
    void shouldRejectBlankVersionTag() {
        final ManageMintLifecycleUseCase.ManageMintLifecycleRequest request =
            new ManageMintLifecycleUseCase.ManageMintLifecycleRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", LifecycleCommand.CREATE, " ");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures a valid request reaches the unsupported branch.
    void shouldReachUnsupportedOperationForValidRequest() {
        assertThrows(UnsupportedOperationException.class, () -> interactor.handle(VALID_REQUEST));
    }
}
