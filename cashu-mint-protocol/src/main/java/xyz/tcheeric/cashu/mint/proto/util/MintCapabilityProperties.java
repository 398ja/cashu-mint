package xyz.tcheeric.cashu.mint.proto.util;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The deployment-specific numbers that appear in the NUT-06 {@code nuts} map:
 * which payment methods this mint runs, the amount limits it enforces, and how
 * long it keeps cached responses.
 *
 * <p>Which NUTs are advertised is not configurable — that is derived from the
 * wiring, see {@code NutSupport}. Only the operational values a deployment can
 * legitimately vary live here.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
@Component
@ConfigurationProperties(prefix = "mint.capabilities")
@Getter
@Setter
public class MintCapabilityProperties {

    private static final String DEFAULT_METHOD = "bolt11";
    private static final String DEFAULT_UNIT = "sat";
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(15);

    private List<PaymentMethodLimits> mintMethods = new ArrayList<>(List.of(defaultMethod()));

    private List<PaymentMethodLimits> meltMethods = new ArrayList<>(List.of(defaultMethod()));

    private boolean mintDisabled;

    private boolean meltDisabled;

    /**
     * How long a cached response stays replayable, advertised as the NUT-19
     * {@code ttl} in seconds.
     */
    private Duration cachedResponseTtl = DEFAULT_CACHE_TTL;

    private static PaymentMethodLimits defaultMethod() {
        PaymentMethodLimits limits = new PaymentMethodLimits();
        limits.setMethod(DEFAULT_METHOD);
        limits.setUnit(DEFAULT_UNIT);
        return limits;
    }

    /**
     * Returns the NUT-19 TTL in whole seconds, as the spec expects.
     *
     * @return the cached-response lifetime in seconds
     */
    public long cachedResponseTtlSeconds() {
        return cachedResponseTtl.toSeconds();
    }

    /**
     * One advertised payment method and the amount range the mint honours on it.
     */
    @Getter
    @Setter
    public static class PaymentMethodLimits {

        private static final int NO_LOWER_LIMIT = 0;
        private static final int DEFAULT_MAX_AMOUNT = 10_000;

        private String method = DEFAULT_METHOD;

        private String unit = DEFAULT_UNIT;

        private int minAmount = NO_LOWER_LIMIT;

        private int maxAmount = DEFAULT_MAX_AMOUNT;
    }
}
