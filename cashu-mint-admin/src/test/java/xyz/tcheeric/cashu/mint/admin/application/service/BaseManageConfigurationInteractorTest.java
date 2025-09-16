package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationCommand;

class BaseManageConfigurationInteractorTest {

    private static final ManageConfigurationUseCase.ManageConfigurationRequest VALID_REQUEST =
        new ManageConfigurationUseCase.ManageConfigurationRequest("123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174001", "2", ConfigurationCommand.APPLY, "v1");

    private final BaseManageConfigurationInteractor interactor = new BaseManageConfigurationInteractor();

    @Test
    // Ensures handle rejects null requests.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.handle(null));
    }

    @Test
    // Ensures handle validates the mint identifier.
    void shouldRejectInvalidMintId() {
        final ManageConfigurationUseCase.ManageConfigurationRequest request =
            new ManageConfigurationUseCase.ManageConfigurationRequest("invalid",
                "123e4567-e89b-12d3-a456-426614174001", "1", ConfigurationCommand.APPLY, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the operator identifier.
    void shouldRejectInvalidOperatorId() {
        final ManageConfigurationUseCase.ManageConfigurationRequest request =
            new ManageConfigurationUseCase.ManageConfigurationRequest("123e4567-e89b-12d3-a456-426614174000",
                "not-a-uuid", "1", ConfigurationCommand.APPLY, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the target revision format.
    void shouldRejectInvalidTargetRevision() {
        final ManageConfigurationUseCase.ManageConfigurationRequest request =
            new ManageConfigurationUseCase.ManageConfigurationRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", "abc", ConfigurationCommand.APPLY, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the command presence.
    void shouldRejectNullCommand() {
        final ManageConfigurationUseCase.ManageConfigurationRequest request =
            new ManageConfigurationUseCase.ManageConfigurationRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", "1", null, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the version tag.
    void shouldRejectBlankVersionTag() {
        final ManageConfigurationUseCase.ManageConfigurationRequest request =
            new ManageConfigurationUseCase.ManageConfigurationRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", "1", ConfigurationCommand.APPLY, " ");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures a valid request reaches the unsupported branch.
    void shouldReachUnsupportedOperationForValidRequest() {
        assertThrows(UnsupportedOperationException.class, () -> interactor.handle(VALID_REQUEST));
    }
}
