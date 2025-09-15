package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.ManageNotificationsRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase.NotificationCommand;

class BaseManageNotificationsInteractorTest {

    private BaseManageNotificationsInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new BaseManageNotificationsInteractor();
    }

    // Ensures invalid mint identifiers are rejected during validation.
    @Test
    void shouldRejectInvalidMintId() {
        final ManageNotificationsRequest request = new ManageNotificationsRequest("invalid",
            validPolicyId(),
            NotificationCommand.CREATE_POLICY,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mint identifier must be a valid UUID");
    }

    // Ensures invalid policy identifiers are rejected during validation.
    @Test
    void shouldRejectInvalidPolicyId() {
        final ManageNotificationsRequest request = new ManageNotificationsRequest(validMintId(),
            "policy",
            NotificationCommand.CREATE_POLICY,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("policy id must be a valid UUID");
    }

    // Ensures null notification commands are rejected.
    @Test
    void shouldRejectNullNotificationCommand() {
        final ManageNotificationsRequest request = new ManageNotificationsRequest(validMintId(),
            validPolicyId(),
            null,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("notification command must not be null");
    }

    // Ensures blank version tags are rejected prior to throwing unsupported behaviour.
    @Test
    void shouldRejectBlankVersionTag() {
        final ManageNotificationsRequest request = new ManageNotificationsRequest(validMintId(),
            validPolicyId(),
            NotificationCommand.CREATE_POLICY,
            " ");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version tag must not be blank");
    }

    // Ensures valid payloads pass validation and then raise the unsupported stub exception.
    @Test
    void shouldThrowUnsupportedAfterValidation() {
        final ManageNotificationsRequest request = new ManageNotificationsRequest(validMintId(),
            validPolicyId(),
            NotificationCommand.CREATE_POLICY,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("ManageNotificationsUseCase has not been implemented yet");
    }

    private String validMintId() {
        return UUID.randomUUID().toString();
    }

    private String validPolicyId() {
        return UUID.randomUUID().toString();
    }
}
