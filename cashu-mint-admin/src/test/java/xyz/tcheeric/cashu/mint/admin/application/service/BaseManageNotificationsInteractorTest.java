package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.NotificationCommand;

class BaseManageNotificationsInteractorTest {

    private static final ManageNotificationsUseCase.ManageNotificationsRequest VALID_REQUEST =
        new ManageNotificationsUseCase.ManageNotificationsRequest("123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174001", NotificationCommand.CREATE_POLICY, "v1");

    private final BaseManageNotificationsInteractor interactor = new BaseManageNotificationsInteractor();

    @Test
    // Ensures handle rejects null requests.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.handle(null));
    }

    @Test
    // Ensures handle validates the mint identifier.
    void shouldRejectInvalidMintId() {
        final ManageNotificationsUseCase.ManageNotificationsRequest request =
            new ManageNotificationsUseCase.ManageNotificationsRequest("invalid",
                "123e4567-e89b-12d3-a456-426614174001", NotificationCommand.CREATE_POLICY, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the policy identifier.
    void shouldRejectInvalidPolicyId() {
        final ManageNotificationsUseCase.ManageNotificationsRequest request =
            new ManageNotificationsUseCase.ManageNotificationsRequest("123e4567-e89b-12d3-a456-426614174000",
                "not-a-uuid", NotificationCommand.CREATE_POLICY, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the command presence.
    void shouldRejectNullCommand() {
        final ManageNotificationsUseCase.ManageNotificationsRequest request =
            new ManageNotificationsUseCase.ManageNotificationsRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", null, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the version tag.
    void shouldRejectBlankVersionTag() {
        final ManageNotificationsUseCase.ManageNotificationsRequest request =
            new ManageNotificationsUseCase.ManageNotificationsRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", NotificationCommand.CREATE_POLICY, " ");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures a valid request reaches the unsupported branch.
    void shouldReachUnsupportedOperationForValidRequest() {
        assertThrows(UnsupportedOperationException.class, () -> interactor.handle(VALID_REQUEST));
    }
}
