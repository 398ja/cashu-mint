package xyz.tcheeric.cashu.mint.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConfigurationRevisionIdTest {

    @Test
    // Ensures factory enforces positive values and exposes the numeric value.
    void shouldCreateFromPositiveValue() {
        final ConfigurationRevisionId revisionId = ConfigurationRevisionId.of(1L);

        assertThat(revisionId.value()).isEqualTo(1L);
    }

    @Test
    // Ensures negative values are rejected by the factory.
    void shouldRejectNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> ConfigurationRevisionId.of(0));
    }

    @Test
    // Ensures next increments the revision number.
    void shouldAdvanceToNextRevision() {
        final ConfigurationRevisionId revisionId = ConfigurationRevisionId.of(5L);

        assertThat(revisionId.next().value()).isEqualTo(6L);
    }

    @Test
    // Ensures isAfter compares revision ordering correctly.
    void shouldDetermineWhenRevisionIsAfterAnother() {
        final ConfigurationRevisionId current = ConfigurationRevisionId.of(5L);
        final ConfigurationRevisionId earlier = ConfigurationRevisionId.of(4L);

        assertThat(current.isAfter(earlier)).isTrue();
        assertThat(earlier.isAfter(current)).isFalse();
    }

    @Test
    // Ensures comparison is delegated to the numeric value.
    void shouldCompareUsingNumericValue() {
        final ConfigurationRevisionId lower = ConfigurationRevisionId.of(1L);
        final ConfigurationRevisionId higher = ConfigurationRevisionId.of(2L);

        assertThat(lower.compareTo(higher)).isLessThan(0);
        assertThat(higher.compareTo(lower)).isGreaterThan(0);
        assertThat(lower.compareTo(ConfigurationRevisionId.of(1L))).isZero();
    }
}
