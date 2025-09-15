package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ManageConfigurationRequest;

class BaseManageConfigurationInteractorTest {

    private BaseManageConfigurationInteractor interactor;

    @BeforeEach
    void setUp() {
        interactor = new BaseManageConfigurationInteractor();
    }

    // Ensures a malformed mint identifier is rejected before any work is attempted.
    @Test
    void shouldRejectInvalidMintId() {
        final ManageConfigurationRequest request = new ManageConfigurationRequest("invalid", validOperatorId(), "2",
            ConfigurationCommand.APPLY,
            "v2");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mint identifier must be a valid UUID");
    }

    // Ensures operator identifiers must be valid UUID values.
    @Test
    void shouldRejectInvalidOperatorId() {
        final ManageConfigurationRequest request = new ManageConfigurationRequest(validMintId(), "operator",
            "2",
            ConfigurationCommand.APPLY,
            "v2");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operator id must be a valid UUID");
    }

    // Ensures non-numeric configuration revisions are rejected during validation.
    @Test
    void shouldRejectNonNumericRevision() {
        final ManageConfigurationRequest request = new ManageConfigurationRequest(validMintId(),
            validOperatorId(),
            "revision",
            ConfigurationCommand.APPLY,
            "v2");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configuration revision must be a positive integer");
    }

    // Ensures null configuration commands are rejected during validation.
    @Test
    void shouldRejectNullConfigurationCommand() {
        final ManageConfigurationRequest request = new ManageConfigurationRequest(validMintId(),
            validOperatorId(),
            "2",
            null,
            "v2");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configuration command must not be null");
    }

    // Ensures blank version tags are rejected before hitting the unsupported stub.
    @Test
    void shouldRejectBlankVersionTag() {
        final ManageConfigurationRequest request = new ManageConfigurationRequest(validMintId(),
            validOperatorId(),
            "2",
            ConfigurationCommand.APPLY,
            " ");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version tag must not be blank");
    }

    // Ensures valid requests progress through validation and raise the unsupported stub exception.
    @Test
    void shouldThrowUnsupportedAfterValidation() {
        final ManageConfigurationRequest request = new ManageConfigurationRequest(validMintId(),
            validOperatorId(),
            "2",
            ConfigurationCommand.APPLY,
            "v2");

        assertThatThrownBy(() -> interactor.handle(request))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("ManageConfigurationUseCase has not been implemented yet");
    }

    private String validMintId() {
        return UUID.randomUUID().toString();
    }

    private String validOperatorId() {
        return UUID.randomUUID().toString();
    }
}
