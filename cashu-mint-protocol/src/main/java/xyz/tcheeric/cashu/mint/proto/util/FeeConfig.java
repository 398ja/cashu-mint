package xyz.tcheeric.cashu.mint.proto.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility for accessing fee reserve configuration values.
 */
public final class FeeConfig {

    private static final Properties PROPERTIES = new Properties();
    private static final double DEFAULT_PERCENT = 0.05d;

    static {
        try (InputStream input = FeeConfig.class.getClassLoader().getResourceAsStream("proto.properties")) {
            if (input != null) {
                PROPERTIES.load(input);
            }
        } catch (IOException ignored) {
            // Ignore, defaults will be used
        }
    }

    private FeeConfig() {
    }

    /**
     * Returns the configured fee reserve percentage used for melts.
     *
     * @return the fee reserve percent as a decimal (e.g. 0.05 for 5%)
     */
    public static double getFeeReservePercent() {
        String value = PROPERTIES.getProperty("cashu.melt.fee-reserve-percent", String.valueOf(DEFAULT_PERCENT));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return DEFAULT_PERCENT;
        }
    }
}
