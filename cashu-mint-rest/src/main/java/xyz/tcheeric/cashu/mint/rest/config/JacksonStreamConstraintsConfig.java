package xyz.tcheeric.cashu.mint.rest.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Parser limits matching the shape NUT messages actually have (AppSec finding L-1, issue #428).
 *
 * <h2>Why the defaults are not enough</h2>
 *
 * <p>Jackson's defaults were measured rather than assumed. On the resolved {@code jackson-core}
 * 2.18.x they are:
 *
 * <pre>
 *   maxNestingDepth = 1000
 *   maxStringLength = 20_000_000   (20 MB)
 *   maxNumberLength = 1000
 * </pre>
 *
 * <p>Two of those three exceed the largest body the mint will accept — Tomcat is capped at 2 MiB
 * — so they can never fire. The body cap is the real limit and Jackson's own guards are inert.
 * That is not an open door; it is a guard positioned behind a narrower one, which means it
 * contributes nothing if the body cap is ever raised, bypassed, or if some path parses JSON that
 * did not arrive through Tomcat.
 *
 * <p>The values below are chosen from the protocol rather than from a general-purpose default.
 * NUT request bodies are shallow and fixed-form: a swap is an object containing two arrays of
 * flat objects, so real nesting is about four levels. Amounts are small integers, and the longest
 * legitimate string is a bolt11 invoice or a base64 token, comfortably inside 100 kB.
 *
 * <h2>Relationship to the jackson bump</h2>
 *
 * <p>GHSA-r7wm-3cxj-wff9 is an async-parser bypass of {@code maxNumberLength} in jackson-core
 * before 2.18.8. Tightening this bound on a vulnerable version would be tightening something
 * that can be walked around, so the dependency bump (issue #436) is what makes this limit hold.
 */
@Slf4j
@Configuration
public class JacksonStreamConstraintsConfig {

    /**
     * Deepest nesting a NUT message legitimately reaches, with room to spare.
     *
     * <p>A swap request is object → array → object → value, so four. Twenty leaves generous
     * headroom for a future NUT while still refusing the deeply-nested input that makes a
     * recursive-descent parser expensive.
     */
    static final int MAX_NESTING_DEPTH = 20;

    /**
     * Longest string value accepted, in characters.
     *
     * <p>The longest legitimate one is a bolt11 invoice or an encoded token — hundreds of bytes,
     * occasionally a few kilobytes. 100 kB is two orders of magnitude above that and two orders
     * below the 20 MB default.
     */
    static final int MAX_STRING_LENGTH = 100_000;

    /**
     * Longest numeric literal accepted, in characters.
     *
     * <p>Cashu amounts are small integers. A 100-digit number is not a NUT amount, and this is
     * the bound GHSA-r7wm-3cxj-wff9 was a bypass of.
     */
    static final int MAX_NUMBER_LENGTH = 100;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer streamReadConstraintsCustomizer() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();

        log.info("Jackson stream constraints: maxNestingDepth={} maxStringLength={} "
                        + "maxNumberLength={}",
                MAX_NESTING_DEPTH, MAX_STRING_LENGTH, MAX_NUMBER_LENGTH);

        return builder -> builder.postConfigurer(
                mapper -> mapper.getFactory().setStreamReadConstraints(constraints));
    }
}
