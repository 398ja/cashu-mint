package xyz.tcheeric.cashu.mint.jpa.crypto;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T024 — microbenchmark asserting hash p99 ≤ 1ms (SC-006 /
 * FR-012). Lightweight in-process timing rather than full JMH harness
 * — we only need to catch a regression of an order of magnitude, not
 * micro-tune the implementation.
 */
class HmacSha256IdentityHasherBenchmarkTest {

    private static final int ITERATIONS = 100_000;
    private static final long ONE_MS_NANOS = 1_000_000L;

    @Test
    void p99HashLatencyIsUnderOneMillisecond() {
        HmacSha256IdentityHasher h = new HmacSha256IdentityHasher(
                "9f8a2c1b7e4d6a3f0c5b9d8e7f6a4c2b1d8e9f7a3c5b2d4e6f1a8c0b9d7e5f3a");
        h.validateSalt();
        IdentityHasher hasher = h;

        // Pre-generate inputs so UUID.randomUUID() doesn't dominate the timing.
        String[] inputs = new String[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            inputs[i] = "npub1-" + UUID.randomUUID();
        }

        // Warm the JIT.
        for (int i = 0; i < 1_000; i++) {
            hasher.hash(inputs[i]);
        }

        long[] timings = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            hasher.hash(inputs[i]);
            timings[i] = System.nanoTime() - start;
        }

        Arrays.sort(timings);
        long p99 = timings[(int) (ITERATIONS * 0.99)];
        long p999 = timings[(int) (ITERATIONS * 0.999)];

        assertThat(p99)
                .as("p99 hash latency must be < 1ms (FR-012 / SC-006); actual p99=%dns p99.9=%dns",
                        p99, p999)
                .isLessThan(ONE_MS_NANOS);
    }
}
