package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AccessCommand;

class BaseAdministerAccessInteractorTest {

    private static final AdministerAccessUseCase.AdministerAccessRequest VALID_REQUEST =
        new AdministerAccessUseCase.AdministerAccessRequest("123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174001", AccessCommand.PROVISION, "v1");

    private final BaseAdministerAccessInteractor interactor = new BaseAdministerAccessInteractor();

    @Test
    // Ensures handle rejects null requests.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.handle(null));
    }

    @Test
    // Ensures handle validates the operator identifier.
    void shouldRejectInvalidOperatorId() {
        final AdministerAccessUseCase.AdministerAccessRequest request =
            new AdministerAccessUseCase.AdministerAccessRequest("not-a-uuid",
                "123e4567-e89b-12d3-a456-426614174001", AccessCommand.PROVISION, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the target account identifier.
    void shouldRejectInvalidTargetAccountId() {
        final AdministerAccessUseCase.AdministerAccessRequest request =
            new AdministerAccessUseCase.AdministerAccessRequest("123e4567-e89b-12d3-a456-426614174000",
                " ", AccessCommand.PROVISION, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the command presence.
    void shouldRejectNullCommand() {
        final AdministerAccessUseCase.AdministerAccessRequest request =
            new AdministerAccessUseCase.AdministerAccessRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", null, "v1");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures handle validates the version tag.
    void shouldRejectBlankVersionTag() {
        final AdministerAccessUseCase.AdministerAccessRequest request =
            new AdministerAccessUseCase.AdministerAccessRequest("123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174001", AccessCommand.PROVISION, " ");

        assertThrows(IllegalArgumentException.class, () -> interactor.handle(request));
    }

    @Test
    // Ensures a fully valid request reaches the unimplemented branch.
    void shouldReachUnsupportedOperationForValidRequest() {
        assertThrows(UnsupportedOperationException.class, () -> interactor.handle(VALID_REQUEST));
    }
}
