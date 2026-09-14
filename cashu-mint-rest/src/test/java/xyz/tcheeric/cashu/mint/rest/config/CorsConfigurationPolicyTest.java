package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AppSec finding I-1 (issue #430): the CORS wildcard must be a decision, not a default.
 *
 * <p>The property used to default to {@code *} with a warning logged, which made "nobody
 * configured this" indistinguishable from "we chose to allow any origin". `*` remains perfectly
 * defensible for a public NUT surface — browsers refuse it on credentialed requests, and these
 * endpoints carry none — so the fix is not to forbid it but to require someone to say so.
 */
class CorsConfigurationPolicyTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    /** Unset outside the local profile stops startup rather than guessing a policy. */
    @Test
    void unsetOriginsFailStartupInANonLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> securityConfig.corsConfigurationSource("", environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashu.mint.cors.allowed-origins is unset");
    }

    /** The local profile still boots unconfigured, so development is unaffected. */
    @Test
    void unsetOriginsAreToleratedUnderTheLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertThatCode(() -> securityConfig.corsConfigurationSource("", environment))
                .doesNotThrowAnyException();
    }

    /**
     * An explicit {@code *} is accepted in production. The gate converts a default into a
     * decision; it does not remove the option.
     */
    @Test
    void anExplicitWildcardIsAcceptedInProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        CorsConfigurationSource source = securityConfig.corsConfigurationSource("*", environment);

        assertThat(originPatternsFor(source)).contains("*");
    }

    /** Named origins are applied as given, and do not silently become a wildcard. */
    @Test
    void namedOriginsAreAppliedExactly() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        CorsConfigurationSource source = securityConfig.corsConfigurationSource(
                "https://wallet.example, https://app.example", environment);

        CorsConfiguration config = configFor(source);
        assertThat(config.getAllowedOrigins())
                .containsExactly("https://wallet.example", "https://app.example");
        assertThat(config.getAllowedOriginPatterns())
                .as("named origins must not also open a wildcard pattern")
                .isNullOrEmpty();
    }

    private static CorsConfiguration configFor(CorsConfigurationSource source) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/info");
        CorsConfiguration config = source.getCorsConfiguration(request);
        assertThat(config).as("a CORS policy is registered for /v1/**").isNotNull();
        return config;
    }

    private static java.util.List<String> originPatternsFor(CorsConfigurationSource source) {
        CorsConfiguration config = configFor(source);
        return config.getAllowedOriginPatterns() == null
                ? java.util.List.of()
                : config.getAllowedOriginPatterns();
    }

    /**
     * A wildcard mixed with named origins opens the surface to everything.
     *
     * <p>Worth pinning because the result is not obvious from the configuration. Writing
     * {@code https://wallet.example,*} reads like "these origins, plus something else", but a
     * wildcard subsumes every named entry, so the named ones have no effect. That is the correct
     * CORS reading, and it is also how an operator ends up publishing an open policy while
     * believing it is restricted -- so it should be a pinned, visible behaviour rather than a
     * surprise discovered from a browser console.
     */
    @Test
    void aWildcardAmongNamedOriginsOpensEverything() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        CorsConfigurationSource source =
                securityConfig.corsConfigurationSource("https://wallet.example,*", environment);

        assertThat(originPatternsFor(source)).contains("*");
        assertThat(configFor(source).getAllowedOrigins())
                .as("named origins are subsumed by the wildcard, not applied alongside it")
                .isNullOrEmpty();
    }
}
