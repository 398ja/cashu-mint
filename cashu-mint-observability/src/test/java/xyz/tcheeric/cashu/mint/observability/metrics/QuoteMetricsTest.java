package xyz.tcheeric.cashu.mint.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuoteMetrics}.
 *
 * Verifies that quote metrics are correctly recorded.
 */
class QuoteMetricsTest {

    private SimpleMeterRegistry registry;
    private QuoteMetrics quoteMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        quoteMetrics = new QuoteMetrics(registry);
    }

    @Test
    void recordMintQuoteCreated_incrementsCounterAndGauge() {
        // When a mint quote is created
        quoteMetrics.recordMintQuoteCreated("bolt11", 1000);

        // Then the created counter is incremented
        Counter counter = registry.find("cashu_mint_quotes_created_total")
                .tag("type", "mint")
                .tag("method", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the amount counter reflects the amount
        Counter amountCounter = registry.find("cashu_mint_quotes_amount_total")
                .tag("type", "mint")
                .counter();
        assertThat(amountCounter).isNotNull();
        assertThat(amountCounter.count()).isEqualTo(1000.0);

        // And the active gauge is incremented
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(1);
    }

    @Test
    void recordMeltQuoteCreated_incrementsCounterAndGauge() {
        // When a melt quote is created
        quoteMetrics.recordMeltQuoteCreated("bolt11", 500);

        // Then the created counter is incremented
        Counter counter = registry.find("cashu_mint_quotes_created_total")
                .tag("type", "melt")
                .tag("method", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the amount counter reflects the amount
        Counter amountCounter = registry.find("cashu_mint_quotes_amount_total")
                .tag("type", "melt")
                .counter();
        assertThat(amountCounter).isNotNull();
        assertThat(amountCounter.count()).isEqualTo(500.0);

        // And the active gauge is incremented
        assertThat(quoteMetrics.getActiveMeltQuotes()).isEqualTo(1);
    }

    @Test
    void recordMintQuoteCompleted_incrementsCounterAndDecrementsGauge() {
        // Given an active mint quote
        quoteMetrics.recordMintQuoteCreated("bolt11", 1000);

        // When the quote is completed
        quoteMetrics.recordMintQuoteCompleted("bolt11", 1000);

        // Then the completed counter is incremented
        Counter counter = registry.find("cashu_mint_quotes_completed_total")
                .tag("type", "mint")
                .tag("method", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the completed amount counter reflects the amount
        Counter amountCounter = registry.find("cashu_mint_quotes_completed_amount_total")
                .tag("type", "mint")
                .counter();
        assertThat(amountCounter).isNotNull();
        assertThat(amountCounter.count()).isEqualTo(1000.0);

        // And the active gauge is decremented
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(0);
    }

    @Test
    void recordMeltQuoteCompleted_incrementsCounterAndDecrementsGauge() {
        // Given an active melt quote
        quoteMetrics.recordMeltQuoteCreated("bolt11", 500);

        // When the quote is completed
        quoteMetrics.recordMeltQuoteCompleted("bolt11", 500);

        // Then the completed counter is incremented
        Counter counter = registry.find("cashu_mint_quotes_completed_total")
                .tag("type", "melt")
                .tag("method", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the active gauge is decremented
        assertThat(quoteMetrics.getActiveMeltQuotes()).isEqualTo(0);
    }

    @Test
    void recordQuoteExpired_incrementsCounterAndDecrementsGauge() {
        // Given active quotes
        quoteMetrics.recordMintQuoteCreated("bolt11", 1000);
        quoteMetrics.recordMeltQuoteCreated("bolt11", 500);

        // When quotes expire
        quoteMetrics.recordQuoteExpired("mint", "bolt11");
        quoteMetrics.recordQuoteExpired("melt", "bolt11");

        // Then the expired counters are incremented
        Counter mintExpired = registry.find("cashu_mint_quotes_expired_total")
                .tag("type", "mint")
                .counter();
        assertThat(mintExpired).isNotNull();
        assertThat(mintExpired.count()).isEqualTo(1.0);

        Counter meltExpired = registry.find("cashu_mint_quotes_expired_total")
                .tag("type", "melt")
                .counter();
        assertThat(meltExpired).isNotNull();
        assertThat(meltExpired.count()).isEqualTo(1.0);

        // And the active gauges are decremented
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(0);
        assertThat(quoteMetrics.getActiveMeltQuotes()).isEqualTo(0);
    }

    @Test
    void recordQuoteFailed_incrementsCounterWithReason() {
        // Given an active quote
        quoteMetrics.recordMintQuoteCreated("bolt11", 1000);

        // When the quote fails
        quoteMetrics.recordQuoteFailed("mint", "bolt11", "payment_timeout");

        // Then the failed counter is incremented
        Counter counter = registry.find("cashu_mint_quotes_failed_total")
                .tag("type", "mint")
                .tag("method", "bolt11")
                .tag("reason", "payment_timeout")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        // And the active gauge is decremented
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(0);
    }

    @Test
    void recordQuoteProcessingTime_recordsTimer() {
        // When recording processing time
        quoteMetrics.recordQuoteProcessingTime("mint", "bolt11", TimeUnit.MILLISECONDS.toNanos(100));
        quoteMetrics.recordQuoteProcessingTime("mint", "bolt11", TimeUnit.MILLISECONDS.toNanos(200));

        // Then the timer captures the durations
        Timer timer = registry.find("cashu_mint_quotes_processing_duration_seconds")
                .tag("type", "mint")
                .tag("method", "bolt11")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300.0);
    }

    @Test
    void timerSample_measuresQuoteProcessing() {
        // When using timer samples
        Timer.Sample sample = quoteMetrics.startTimer();

        // Simulate some work
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long duration = quoteMetrics.stopTimer(sample, "melt", "bolt11");

        // Then the duration is recorded
        assertThat(duration).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(10));

        Timer timer = registry.find("cashu_mint_quotes_processing_duration_seconds")
                .tag("type", "melt")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void multipleQuotes_tracksActiveCountCorrectly() {
        // Given multiple quotes
        quoteMetrics.recordMintQuoteCreated("bolt11", 100);
        quoteMetrics.recordMintQuoteCreated("bolt11", 200);
        quoteMetrics.recordMintQuoteCreated("bolt11", 300);

        // Then active count is correct
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(3);

        // When one is completed
        quoteMetrics.recordMintQuoteCompleted("bolt11", 100);
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(2);

        // When one expires
        quoteMetrics.recordQuoteExpired("mint", "bolt11");
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(1);

        // When one fails
        quoteMetrics.recordQuoteFailed("mint", "bolt11", "error");
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(0);
    }

    @Test
    void setActiveQuotes_initializesFromDb() {
        // When setting active quotes from DB state
        quoteMetrics.setActiveMintQuotes(5);
        quoteMetrics.setActiveMeltQuotes(3);

        // Then gauges reflect the values
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(5);
        assertThat(quoteMetrics.getActiveMeltQuotes()).isEqualTo(3);

        // And subsequent operations adjust correctly
        quoteMetrics.recordMintQuoteCompleted("bolt11", 100);
        assertThat(quoteMetrics.getActiveMintQuotes()).isEqualTo(4);
    }

    @Test
    void normalizeMethod_lowercasesMethod() {
        // When creating quotes with different method cases
        quoteMetrics.recordMintQuoteCreated("BOLT11", 100);
        quoteMetrics.recordMintQuoteCreated("Bolt11", 200);

        // Then they're tracked under the same normalized method
        Counter counter = registry.find("cashu_mint_quotes_created_total")
                .tag("type", "mint")
                .tag("method", "bolt11")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void differentMethods_trackedSeparately() {
        // Given quotes with different methods
        quoteMetrics.recordMintQuoteCreated("bolt11", 100);
        quoteMetrics.recordMintQuoteCreated("onchain", 200);

        // Then each method has its own counter
        Counter bolt11Counter = registry.find("cashu_mint_quotes_created_total")
                .tag("type", "mint")
                .tag("method", "bolt11")
                .counter();
        assertThat(bolt11Counter).isNotNull();
        assertThat(bolt11Counter.count()).isEqualTo(1.0);

        Counter onchainCounter = registry.find("cashu_mint_quotes_created_total")
                .tag("type", "mint")
                .tag("method", "onchain")
                .counter();
        assertThat(onchainCounter).isNotNull();
        assertThat(onchainCounter.count()).isEqualTo(1.0);
    }
}
