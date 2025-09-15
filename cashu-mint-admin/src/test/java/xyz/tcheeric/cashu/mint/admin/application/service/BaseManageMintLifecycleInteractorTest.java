package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.LifecycleCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase.ManageMintLifecycleRequest;

class BaseManageMintLifecycleInteractorTest {

    private BaseManageMintLifecycleInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new BaseManageMintLifecycleInteractor();
    }

    // Ensures a blank mint identifier is rejected during validation.
    @Test
    void shouldRejectBlankMintId() {
        final ManageMintLifecycleRequest request = new ManageMintLifecycleRequest(" ",
            validOperatorId(),
            LifecycleCommand.CREATE,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mint identifier must not be blank");
    }

    // Ensures malformed operator identifiers are rejected during validation.
    @Test
    void shouldRejectInvalidOperatorId() {
        final ManageMintLifecycleRequest request = new ManageMintLifecycleRequest(validMintId(),
            "not-a-uuid",
            LifecycleCommand.CREATE,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operator id must be a valid UUID");
    }

    // Ensures null lifecycle commands are rejected during validation.
    @Test
    void shouldRejectNullLifecycleCommand() {
        final ManageMintLifecycleRequest request = new ManageMintLifecycleRequest(validMintId(),
            validOperatorId(),
            null,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lifecycle command must not be null");
    }

    // Ensures blank version tags fail fast before reaching unsupported behaviour.
    @Test
    void shouldRejectBlankVersionTag() {
        final ManageMintLifecycleRequest request = new ManageMintLifecycleRequest(validMintId(),
            validOperatorId(),
            LifecycleCommand.CREATE,
            " ");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version tag must not be blank");
    }

    // Ensures valid payloads progress past validation and surface the unsupported stub exception.
    @Test
    void shouldThrowUnsupportedAfterValidation() {
        final ManageMintLifecycleRequest request = new ManageMintLifecycleRequest(validMintId(),
            validOperatorId(),
            LifecycleCommand.CREATE,
            "v1");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("ManageMintLifecycleUseCase has not been implemented yet");
    }

    private String validMintId() {
        return UUID.randomUUID().toString();
    }

    private String validOperatorId() {
        return UUID.randomUUID().toString();
    }
}
