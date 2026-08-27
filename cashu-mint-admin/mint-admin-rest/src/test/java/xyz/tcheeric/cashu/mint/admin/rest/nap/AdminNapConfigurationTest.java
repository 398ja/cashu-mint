package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Super Administrator npub is configuration NAP cannot start without (issue #372).
 */
class AdminNapConfigurationTest {

    private static final String VALID_NPUB =
        "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d";

    private final AdminNapConfiguration configuration = new AdminNapConfiguration();
    private final OperatorAccessRepository operators = Mockito.mock(OperatorAccessRepository.class);

    // Checks an unset npub stops the application rather than leaving it unadministrable.
    @Test
    @DisplayName("A missing super-admin npub fails startup")
    void missingNpubFailsStartup() {
        assertThatThrownBy(() -> configuration.adminAclResolver("  ", operators))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin.security.super-admin-npub is not set");
    }

    // Checks a typo in the npub is caught at startup, not at the first handshake.
    @Test
    @DisplayName("An undecodable super-admin npub fails startup")
    void undecodableNpubFailsStartup() {
        assertThatThrownBy(() -> configuration.adminAclResolver("npub1nonsense", operators))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not a valid npub");
    }

    // Checks the happy path builds the resolver without touching the operator store.
    @Test
    @DisplayName("A valid npub builds the resolver without querying operators")
    void validNpubBuildsResolver() {
        assertThat(configuration.adminAclResolver(VALID_NPUB, operators)).isNotNull();
        Mockito.verifyNoInteractions(operators);
    }
}
