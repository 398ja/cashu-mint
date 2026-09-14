package xyz.tcheeric.cashu.mint.rest.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AppSec finding L-1 (issue #428): parser limits should match the protocol, not Jackson's
 * general-purpose defaults.
 *
 * <p>The defaults — nesting 1000, strings 20 MB, numbers 1000 — are mostly larger than the 2 MiB
 * body cap, so they could never fire. These tests confirm the configured limits both reject what
 * they should and accept ordinary NUT traffic, since a limit that rejects real requests is worse
 * than no limit at all.
 */
class JacksonStreamConstraintsConfigTest {

    private ObjectMapper configuredMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonStreamConstraintsConfig().streamReadConstraintsCustomizer().customize(builder);
        return builder.build();
    }

    /** The limits are actually applied to the mapper Spring will use. */
    @Test
    void theConfiguredLimitsAreAppliedToTheMapper() {
        StreamReadConstraints constraints =
                configuredMapper().getFactory().streamReadConstraints();

        assertThat(constraints.getMaxNestingDepth())
                .isEqualTo(JacksonStreamConstraintsConfig.MAX_NESTING_DEPTH);
        assertThat(constraints.getMaxStringLength())
                .isEqualTo(JacksonStreamConstraintsConfig.MAX_STRING_LENGTH);
        assertThat(constraints.getMaxNumberLength())
                .isEqualTo(JacksonStreamConstraintsConfig.MAX_NUMBER_LENGTH);
    }

    /** They are tighter than the defaults, which is the entire point of configuring them. */
    @Test
    void theLimitsAreTighterThanJacksonsDefaults() {
        StreamReadConstraints defaults = StreamReadConstraints.defaults();

        assertThat(JacksonStreamConstraintsConfig.MAX_NESTING_DEPTH)
                .isLessThan(defaults.getMaxNestingDepth());
        assertThat(JacksonStreamConstraintsConfig.MAX_STRING_LENGTH)
                .isLessThan(defaults.getMaxStringLength());
        assertThat(JacksonStreamConstraintsConfig.MAX_NUMBER_LENGTH)
                .isLessThan(defaults.getMaxNumberLength());
    }

    /** Deeply nested JSON is refused rather than parsed. */
    @Test
    void deeplyNestedJsonIsRejected() {
        String nested = "[".repeat(200) + "]".repeat(200);

        assertThatThrownBy(() -> configuredMapper().readTree(nested))
                .isInstanceOf(StreamConstraintsException.class)
                .hasMessageContaining("Depth");
    }

    /** An enormous string value is refused. */
    @Test
    void anOverlongStringIsRejected() {
        String body = "{\"secret\":\"" + "x".repeat(200_000) + "\"}";

        assertThatThrownBy(() -> configuredMapper().readTree(body))
                .isInstanceOf(StreamConstraintsException.class);
    }

    /**
     * An absurdly long numeric literal is refused. This is the bound that
     * GHSA-r7wm-3cxj-wff9 was a bypass of, which is why the jackson bump (#436) and this limit
     * belong together.
     */
    @Test
    void anOverlongNumberIsRejected() {
        String body = "{\"amount\":" + "9".repeat(500) + "}";

        assertThatThrownBy(() -> configuredMapper().readTree(body))
                .isInstanceOf(StreamConstraintsException.class);
    }

    /**
     * A realistic NUT request parses cleanly.
     *
     * <p>Without this the tests above would pass just as happily against limits set absurdly low,
     * which would break every real client — a limit that rejects legitimate traffic is worse than
     * no limit.
     */
    @Test
    void anOrdinaryNutRequestStillParses() {
        String swap = """
                {"inputs":[{"amount":8,"id":"009a1f293253e41e",
                "secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",
                "C":"02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee"}],
                "outputs":[{"amount":8,"id":"009a1f293253e41e",
                "B_":"02634a2c2b34bec9e8a4aba4361f6bf202d7fa2365379b0840afe249a7a9d71239"}]}
                """;

        assertThatCode(() -> configuredMapper().readTree(swap)).doesNotThrowAnyException();
    }

    /** A bolt11 invoice is the longest string NUT traffic realistically carries. */
    @Test
    void aBolt11InvoiceIsWellInsideTheStringLimit() {
        String invoice = "lnbc" + "1".repeat(2_000);
        String body = "{\"request\":\"" + invoice + "\"}";

        assertThatCode(() -> configuredMapper().readTree(body)).doesNotThrowAnyException();
    }
}
