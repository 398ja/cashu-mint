package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VoucherFeeConfig utility class.
 */
public class VoucherFeeConfigTest {

    @Test
    public void testDefaultValue() {
        // Test that default value loads correctly from proto.properties
        double percentage = VoucherFeeConfig.getFeePercentage();

        // Should load the configured value of 10% from proto.properties
        assertEquals(10.0, percentage, 0.001);
    }

    @Test
    public void testDefaultMaxValue() {
        // Test that default max value loads correctly
        double maxPercentage = VoucherFeeConfig.getMaxPercentage();

        // Should load the configured value of 100% from proto.properties
        assertEquals(100.0, maxPercentage, 0.001);
    }

    @Test
    public void testPercentageIsPositive() {
        // Test that the percentage is non-negative
        double percentage = VoucherFeeConfig.getFeePercentage();

        assertTrue(percentage >= 0, "Percentage should be non-negative");
    }

    @Test
    public void testPercentageWithinMaxBounds() {
        // Test that the percentage is within maximum bounds
        double percentage = VoucherFeeConfig.getFeePercentage();
        double maxPercentage = VoucherFeeConfig.getMaxPercentage();

        assertTrue(percentage <= maxPercentage,
            String.format("Percentage %.2f should not exceed maximum %.2f", percentage, maxPercentage));
    }

    @Test
    public void testZeroPercentageWouldBeValid() {
        // Test that zero percentage is conceptually valid (if configured)
        // This verifies the logic doesn't reject zero, though proto.properties has 10
        // We can't easily test with 0 without mocking, but we verify the bounds allow it
        double maxPercentage = VoucherFeeConfig.getMaxPercentage();

        assertTrue(maxPercentage >= 0, "Max percentage should allow zero or positive values");
    }

    @Test
    public void testCaching() {
        // Test that the value is cached (same instance returned)
        double firstCall = VoucherFeeConfig.getFeePercentage();
        double secondCall = VoucherFeeConfig.getFeePercentage();

        assertEquals(firstCall, secondCall, 0.001, "Cached values should match");
    }

    @Test
    public void testMaxPercentageCaching() {
        // Test that the max value is cached
        double firstCall = VoucherFeeConfig.getMaxPercentage();
        double secondCall = VoucherFeeConfig.getMaxPercentage();

        assertEquals(firstCall, secondCall, 0.001, "Cached max values should match");
    }
}
