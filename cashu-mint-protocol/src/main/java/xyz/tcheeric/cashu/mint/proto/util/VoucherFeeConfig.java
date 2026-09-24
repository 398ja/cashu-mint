package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility for accessing voucher mint quote fee percentage configuration.
 *
 * <p>Configuration is loaded with the following precedence:
 * <ol>
 *   <li>System property: {@code -Dvoucher.quote.fee-percent=X}</li>
 *   <li>Environment variable: {@code VOUCHER_QUOTE_FEE_PERCENT}</li>
 *   <li>Property file: {@code voucher.quote.fee-percent} in proto.properties</li>
 *   <li>Default: 10.0 (10%)</li>
 * </ol>
 *
 * <p>The same three sources configure the <strong>minimum</strong> fee
 * ({@code voucher.quote.fee-min-sat} / {@code VOUCHER_QUOTE_FEE_MIN_SAT},
 * default {@code 1}), which is the floor applied after the percentage is
 * computed.
 *
 * <p><strong>Why a minimum exists.</strong> The fee is
 * {@code floor(faceValue * percent / 100)}, so every face value below
 * {@code 100 / percent} rounds to zero: at the default 10% that is anything
 * under 10 sats. A zero fee is not a cheap voucher, it is a zero-amount
 * invoice, and the whole payment chain accepts one: the gateway creates it,
 * it settles trivially, and the mint then refuses its own webhook because a
 * non-positive amount can never match an authorised quote. Observed on
 * staging as 9 stranded quotes generating unbounded webhook rejections.
 */
@Slf4j
public final class VoucherFeeConfig {

    private static final String PROPERTY_KEY = "voucher.quote.fee-percent";
    private static final String PROPERTY_MAX_KEY = "voucher.quote.fee-percent.max";
    private static final String ENV_KEY = "VOUCHER_QUOTE_FEE_PERCENT";
    private static final String ENV_MAX_KEY = "VOUCHER_QUOTE_FEE_PERCENT_MAX";
    private static final String SYSTEM_PROP_KEY = "voucher.quote.fee-percent";
    private static final String SYSTEM_PROP_MAX_KEY = "voucher.quote.fee-percent.max";
    private static final String PROPERTY_MIN_KEY = "voucher.quote.fee-min-sat";
    private static final String ENV_MIN_KEY = "VOUCHER_QUOTE_FEE_MIN_SAT";
    private static final String SYSTEM_PROP_MIN_KEY = "voucher.quote.fee-min-sat";

    private static final double DEFAULT_PERCENTAGE = 10.0;
    private static final double DEFAULT_MAX_PERCENTAGE = 100.0;

    /**
     * The smallest fee a voucher may be charged, in the quote's unit.
     *
     * <p>One rather than zero, because zero is not a price: it produces an
     * invoice nothing can pay for and the mint later refuses. Settable to 0
     * for a deployment that genuinely wants free vouchers, which then has to
     * handle the zero-amount path deliberately rather than by accident.
     */
    private static final long DEFAULT_MIN_FEE = 1L;

    private static final Properties PROPERTIES = new Properties();
    private static Double cachedPercentage = null;
    private static Double cachedMaxPercentage = null;
    private static Long cachedMinFee = null;

    static {
        try (InputStream input = VoucherFeeConfig.class.getClassLoader().getResourceAsStream("proto.properties")) {
            if (input != null) {
                PROPERTIES.load(input);
            }
        } catch (IOException e) {
            log.warn("Failed to load proto.properties, using defaults", e);
        }
    }

    private VoucherFeeConfig() {
    }

    /**
     * Returns the configured voucher mint quote fee percentage.
     *
     * <p>The percentage is returned as an actual percent value (e.g., 10.0 for 10%),
     * not as a decimal fraction (0.10).
     *
     * @return the fee percentage (e.g., 10.0 for 10%)
     * @throws IllegalArgumentException if the configured percentage is invalid
     */
    public static double getFeePercentage() {
        if (cachedPercentage != null) {
            return cachedPercentage;
        }

        double percentage = loadPercentage();
        validatePercentage(percentage);

        cachedPercentage = percentage;
        log.info("Voucher mint quote fee percentage: {}%", percentage);

        return percentage;
    }

    /**
     * Returns the maximum allowed fee percentage.
     *
     * @return the maximum fee percentage
     */
    public static double getMaxPercentage() {
        if (cachedMaxPercentage != null) {
            return cachedMaxPercentage;
        }

        double maxPercentage = loadMaxPercentage();

        cachedMaxPercentage = maxPercentage;
        log.debug("Voucher mint quote maximum fee percentage: {}%", maxPercentage);

        return maxPercentage;
    }

    /**
     * Returns the minimum fee, in the quote's unit, applied after the
     * percentage is computed.
     *
     * @return the minimum fee (default 1)
     */
    public static long getMinimumFee() {
        if (cachedMinFee != null) {
            return cachedMinFee;
        }

        long minimum = loadMinimumFee();
        if (minimum < 0) {
            // A negative floor would raise the fee below zero on the next
            // change to the calculator. Refuse it rather than carry it.
            throw new IllegalArgumentException(
                String.format("Voucher minimum fee cannot be negative: %d", minimum));
        }

        cachedMinFee = minimum;
        log.info("Voucher mint quote minimum fee: {}", minimum);

        return minimum;
    }

    /**
     * Validates that the percentage is within acceptable bounds.
     *
     * @param percentage the percentage to validate
     * @throws IllegalArgumentException if percentage is invalid
     */
    private static void validatePercentage(double percentage) {
        if (percentage < 0) {
            throw new IllegalArgumentException(
                String.format("Voucher fee percentage cannot be negative: %.2f", percentage));
        }

        double max = getMaxPercentage();
        if (percentage > max) {
            throw new IllegalArgumentException(
                String.format("Voucher fee percentage %.2f exceeds maximum %.2f", percentage, max));
        }
    }

    /**
     * Loads the fee percentage from configuration sources with precedence.
     *
     * @return the configured percentage
     */
    private static double loadPercentage() {
        Double value = parseDoubleValue("system property", SYSTEM_PROP_KEY, System.getProperty(SYSTEM_PROP_KEY));
        if (value != null) {
            log.debug("Loaded voucher fee percentage from system property: {}%", value);
            return value;
        }

        value = parseDoubleValue("environment variable", ENV_KEY, System.getenv(ENV_KEY));
        if (value != null) {
            log.debug("Loaded voucher fee percentage from environment variable: {}%", value);
            return value;
        }

        value = parseDoubleValue("property file", PROPERTY_KEY, PROPERTIES.getProperty(PROPERTY_KEY));
        if (value != null) {
            log.debug("Loaded voucher fee percentage from property file: {}%", value);
            return value;
        }

        log.debug("Using default voucher fee percentage: {}%", DEFAULT_PERCENTAGE);
        return DEFAULT_PERCENTAGE;
    }

    /**
     * Loads the maximum fee percentage from configuration sources.
     *
     * @return the configured maximum percentage
     */
    private static double loadMaxPercentage() {
        Double value = parseDoubleValue("system property", SYSTEM_PROP_MAX_KEY, System.getProperty(SYSTEM_PROP_MAX_KEY));
        if (value != null) {
            return value;
        }

        value = parseDoubleValue("environment variable", ENV_MAX_KEY, System.getenv(ENV_MAX_KEY));
        if (value != null) {
            return value;
        }

        value = parseDoubleValue("property file", PROPERTY_MAX_KEY, PROPERTIES.getProperty(PROPERTY_MAX_KEY));
        if (value != null) {
            return value;
        }

        return DEFAULT_MAX_PERCENTAGE;
    }

    /**
     * Loads the minimum fee from configuration sources with the same
     * precedence as the percentage.
     *
     * @return the configured minimum
     */
    private static long loadMinimumFee() {
        Long value = parseLongValue("system property", SYSTEM_PROP_MIN_KEY, System.getProperty(SYSTEM_PROP_MIN_KEY));
        if (value != null) {
            log.debug("Loaded voucher minimum fee from system property: {}", value);
            return value;
        }

        value = parseLongValue("environment variable", ENV_MIN_KEY, System.getenv(ENV_MIN_KEY));
        if (value != null) {
            log.debug("Loaded voucher minimum fee from environment variable: {}", value);
            return value;
        }

        value = parseLongValue("property file", PROPERTY_MIN_KEY, PROPERTIES.getProperty(PROPERTY_MIN_KEY));
        if (value != null) {
            log.debug("Loaded voucher minimum fee from property file: {}", value);
            return value;
        }

        log.debug("Using default voucher minimum fee: {}", DEFAULT_MIN_FEE);
        return DEFAULT_MIN_FEE;
    }

    private static Long parseLongValue(String source, String key, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException e) {
            // Same posture as the percentage: an unparseable override falls
            // back to the default rather than failing the mint at boot, and
            // says so loudly enough to find.
            log.warn("voucher_fee_config invalid {} value '{}' for key {}", source, rawValue, key);
            return null;
        }
    }

    private static Double parseDoubleValue(String source, String key, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException e) {
            log.warn("voucher_fee_config invalid {} value '{}' for key {}", source, rawValue, key);
            return null;
        }
    }
}
