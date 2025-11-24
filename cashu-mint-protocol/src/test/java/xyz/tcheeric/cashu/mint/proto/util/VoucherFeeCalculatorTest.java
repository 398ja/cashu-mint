package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VoucherFeeCalculator utility class.
 */
public class VoucherFeeCalculatorTest {

    @Test
    public void testStandardCalculation() {
        // Test standard calculation: 1000 sats @ 10% = 100 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 10.0);
        assertEquals(100, fee);
    }

    @Test
    public void testLowPercentage() {
        // Test low percentage: 1000 sats @ 1% = 10 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 1.0);
        assertEquals(10, fee);
    }

    @Test
    public void testZeroPercentage() {
        // Test zero percentage: any amount @ 0% = 0 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 0.0);
        assertEquals(0, fee);
    }

    @Test
    public void testZeroAmount() {
        // Test zero amount: 0 sats @ any % = 0 sats
        long fee = VoucherFeeCalculator.calculateFee(0, 10.0);
        assertEquals(0, fee);
    }

    @Test
    public void testFloorRounding() {
        // Test floor rounding: 1005 sats @ 1% = floor(10.05) = 10 sats
        long fee = VoucherFeeCalculator.calculateFee(1005, 1.0);
        assertEquals(10, fee);
    }

    @Test
    public void testFloorRoundingResultsInZero() {
        // Test edge case: 1 sat @ 50% = floor(0.5) = 0 sats
        long fee = VoucherFeeCalculator.calculateFee(1, 50.0);
        assertEquals(0, fee);

        // Test edge case: 99 sats @ 1% = floor(0.99) = 0 sats
        fee = VoucherFeeCalculator.calculateFee(99, 1.0);
        assertEquals(0, fee);
    }

    @Test
    public void test100PercentFee() {
        // Test 100% fee: 1000 sats @ 100% = 1000 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 100.0);
        assertEquals(1000, fee);
    }

    @Test
    public void testLargeAmount() {
        // Test near max safe value: 1 billion sats @ 10% = 100 million sats
        long largeAmount = 1_000_000_000L;
        long fee = VoucherFeeCalculator.calculateFee(largeAmount, 10.0);
        assertEquals(100_000_000L, fee);
    }

    @Test
    public void testVeryLargeAmountWithinBounds() {
        // Test very large amount that should still work: 100 billion sats @ 1%
        long veryLargeAmount = 100_000_000_000L;
        long fee = VoucherFeeCalculator.calculateFee(veryLargeAmount, 1.0);
        assertEquals(1_000_000_000L, fee);
    }

    @Test
    public void testOverflowProtection() {
        // Test amount that would cause overflow
        // Long.MAX_VALUE is approximately 9.2 * 10^18
        // With percentage > 100%, the result would exceed Long.MAX_VALUE
        long unsafeAmount = Long.MAX_VALUE;

        assertThrows(ArithmeticException.class, () -> {
            VoucherFeeCalculator.calculateFee(unsafeAmount, 150.0);
        });

        // Also test with very large amount and high percentage
        assertThrows(ArithmeticException.class, () -> {
            VoucherFeeCalculator.calculateFee(Long.MAX_VALUE / 2, 300.0);
        });
    }

    @Test
    public void testNegativeAmountRejected() {
        // Test that negative amounts are rejected
        assertThrows(IllegalArgumentException.class, () -> {
            VoucherFeeCalculator.calculateFee(-1000, 10.0);
        });
    }

    @Test
    public void testNegativePercentageRejected() {
        // Test that negative percentages are rejected
        assertThrows(IllegalArgumentException.class, () -> {
            VoucherFeeCalculator.calculateFee(1000, -10.0);
        });
    }

    @Test
    public void testFractionalPercentage() {
        // Test fractional percentage: 10000 sats @ 0.5% = 50 sats
        long fee = VoucherFeeCalculator.calculateFee(10000, 0.5);
        assertEquals(50, fee);
    }

    @Test
    public void testVerySmallAmount() {
        // Test very small amount: 5 sats @ 10% = floor(0.5) = 0 sats
        long fee = VoucherFeeCalculator.calculateFee(5, 10.0);
        assertEquals(0, fee);

        // Test: 10 sats @ 10% = 1 sat
        fee = VoucherFeeCalculator.calculateFee(10, 10.0);
        assertEquals(1, fee);
    }

    @Test
    public void testHighPercentage() {
        // Test high percentage: 1000 sats @ 50% = 500 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 50.0);
        assertEquals(500, fee);
    }

    @Test
    public void testMultipleCalculationsConsistent() {
        // Test that multiple calculations with same inputs produce same results
        long fee1 = VoucherFeeCalculator.calculateFee(1000, 10.0);
        long fee2 = VoucherFeeCalculator.calculateFee(1000, 10.0);
        assertEquals(fee1, fee2);
    }

    @Test
    public void testBoundaryAt1Percent() {
        // Test boundary: smallest amount that produces non-zero fee at 1%
        // 100 sats @ 1% = 1 sat
        long fee = VoucherFeeCalculator.calculateFee(100, 1.0);
        assertEquals(1, fee);

        // 99 sats @ 1% = floor(0.99) = 0 sats
        fee = VoucherFeeCalculator.calculateFee(99, 1.0);
        assertEquals(0, fee);
    }

    @Test
    public void testBoundaryAt10Percent() {
        // Test boundary: smallest amount that produces non-zero fee at 10%
        // 10 sats @ 10% = 1 sat
        long fee = VoucherFeeCalculator.calculateFee(10, 10.0);
        assertEquals(1, fee);

        // 9 sats @ 10% = floor(0.9) = 0 sats
        fee = VoucherFeeCalculator.calculateFee(9, 10.0);
        assertEquals(0, fee);
    }

    @Test
    public void testRealWorldExample1() {
        // Test real-world example from spec: 1000 sats @ 10% = 100 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 10.0);
        assertEquals(100, fee);
    }

    @Test
    public void testRealWorldExample2() {
        // Test real-world example from spec: 1000 sats @ 1% = 10 sats
        long fee = VoucherFeeCalculator.calculateFee(1000, 1.0);
        assertEquals(10, fee);
    }
}
