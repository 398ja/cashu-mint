package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * Async configuration for virtual thread support.
 *
 * <p>When virtual threads are enabled, this configures the application's TaskExecutor
 * to use virtual threads for all @Async operations. This ensures consistent behavior
 * across request handling (Tomcat) and async tasks.
 *
 * <p>When virtual threads are disabled, Spring Boot's default thread pool executor is used.
 */
@Configuration
@EnableAsync
@ConditionalOnProperty(name = "spring.threads.virtual.enabled", havingValue = "true", matchIfMissing = true)
public class AsyncConfig {

    /**
     * Creates a TaskExecutor that spawns a new virtual thread for each task.
     *
     * <p>Virtual threads are lightweight and can be created in large numbers without
     * the memory overhead of platform threads. No pool size configuration is needed
     * because virtual threads are designed for unbounded concurrency.
     *
     * @return TaskExecutor using virtual threads
     */
    @Bean
    public TaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
