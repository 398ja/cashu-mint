package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AccessCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessRequest;

class BaseAdministerAccessInteractorTest {

    private BaseAdministerAccessInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new BaseAdministerAccessInteractor();
    }

    // Ensures operator identifiers must be valid UUID strings.
    @Test
    void shouldRejectInvalidOperatorId() {
        final AdministerAccessRequest request = new AdministerAccessRequest("operator",
            validTargetAccountId(),
            AccessCommand.PROVISION,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operator id must be a valid UUID");
    }

    // Ensures target account identifiers must also be valid UUID strings.
    @Test
    void shouldRejectInvalidTargetAccountId() {
        final AdministerAccessRequest request = new AdministerAccessRequest(validOperatorId(),
            "target",
            AccessCommand.PROVISION,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target account id must be a valid UUID");
    }

    // Ensures null access commands are rejected during validation.
    @Test
    void shouldRejectNullAccessCommand() {
        final AdministerAccessRequest request = new AdministerAccessRequest(validOperatorId(),
            validTargetAccountId(),
            null,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access command must not be null");
    }

    // Ensures blank version tags are rejected before reaching unsupported behaviour.
    @Test
    void shouldRejectBlankVersionTag() {
        final AdministerAccessRequest request = new AdministerAccessRequest(validOperatorId(),
            validTargetAccountId(),
            AccessCommand.PROVISION,
            " ");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version tag must not be blank");
    }

    // Ensures valid payloads raise the unsupported stub exception after validation succeeds.
    @Test
    void shouldThrowUnsupportedAfterValidation() {
        final AdministerAccessRequest request = new AdministerAccessRequest(validOperatorId(),
            validTargetAccountId(),
            AccessCommand.PROVISION,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("AdministerAccessUseCase has not been implemented yet");
    }

    private String validOperatorId() {
        return UUID.randomUUID().toString();
    }

    private String validTargetAccountId() {
        return UUID.randomUUID().toString();
    }
}
