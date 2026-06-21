package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * Async configuration.
 *
 * <p>{@code @EnableAsync} is <strong>unconditional</strong> so {@code @Async} is always honoured —
 * otherwise gating it on {@code spring.threads.virtual.enabled} would silently turn every
 * {@code @Async} method (including the fire-and-forget trace producer) into a synchronous call on the
 * request thread when virtual threads are disabled, breaking the "never block the request" guarantee.
 *
 * <p>Only the executor is conditional: when virtual threads are enabled we spawn one per task; when
 * disabled this bean is absent and Spring Boot's auto-configured platform-thread
 * {@code applicationTaskExecutor} pool is used as the fallback.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Creates a TaskExecutor that spawns a new virtual thread for each task.
     *
     * <p>Virtual threads are lightweight and can be created in large numbers without
     * the memory overhead of platform threads. No pool size configuration is needed
     * because virtual threads are designed for unbounded concurrency. When virtual threads
     * are disabled this bean is not created and Spring Boot's default pool executor applies.
     *
     * @return TaskExecutor using virtual threads
     */
    @Bean
    @ConditionalOnProperty(name = "spring.threads.virtual.enabled", havingValue = "true", matchIfMissing = true)
    public TaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
