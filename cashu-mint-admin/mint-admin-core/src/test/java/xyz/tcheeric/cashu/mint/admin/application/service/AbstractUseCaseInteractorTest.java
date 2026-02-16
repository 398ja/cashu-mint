package xyz.tcheeric.cashu.mint.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AbstractUseCaseInteractorTest {

    private final TestInteractor interactor = new TestInteractor();

    @Test
    // Ensures requireRequest returns the provided request when it is non-null.
    void shouldReturnRequestWhenNotNull() {
        final String value = interactor.requireRequest("payload", "test request");

        assertThat(value).isEqualTo("payload");
    }

    @Test
    // Ensures requireRequest throws when the request is null.
    void shouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> interactor.requireRequest(null, "test request"));
    }

    @Test
    // Ensures validateMintId delegates to MintId parsing.
    void shouldValidateMintId() {
        assertThat(interactor.validateMintId("123e4567-e89b-12d3-a456-426614174000")).isNotNull();
    }

    @Test
    // Ensures validateUuid accepts valid UUID strings.
    void shouldValidateUuid() {
        assertThat(interactor.validateUuid("123e4567-e89b-12d3-a456-426614174000", "field")).isNotNull();
    }

    @Test
    // Ensures validateUuid rejects blank identifiers.
    void shouldRejectBlankUuid() {
        assertThrows(IllegalArgumentException.class, () -> interactor.validateUuid(" ", "field"));
    }

    @Test
    // Ensures validateUuid rejects malformed identifiers.
    void shouldRejectInvalidUuid() {
        assertThrows(IllegalArgumentException.class, () -> interactor.validateUuid("not-a-uuid", "field"));
    }

    @Test
    // Ensures validateConfigurationRevision parses numeric values.
    void shouldValidateConfigurationRevision() {
        assertThat(interactor.validateConfigurationRevision("10").value()).isEqualTo(10);
    }

    @Test
    // Ensures validateConfigurationRevision rejects non-numeric input.
    void shouldRejectInvalidConfigurationRevision() {
        assertThrows(IllegalArgumentException.class, () -> interactor.validateConfigurationRevision("abc"));
    }

    @Test
    // Ensures validateConfigurationRevision rejects blank input.
    void shouldRejectBlankConfigurationRevision() {
        assertThrows(IllegalArgumentException.class, () -> interactor.validateConfigurationRevision(" "));
    }

    @Test
    // Ensures validateVersionTag enforces non-blank tags.
    void shouldValidateVersionTag() {
        assertThat(interactor.validateVersionTag("v1")).isEqualTo("v1");
    }

    @Test
    // Ensures validateVersionTag rejects blank values.
    void shouldRejectBlankVersionTag() {
        assertThrows(IllegalArgumentException.class, () -> interactor.validateVersionTag(""));
    }

    private static class TestInteractor extends AbstractUseCaseInteractor { }
}
