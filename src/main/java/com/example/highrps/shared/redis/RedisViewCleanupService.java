package com.example.highrps.shared.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Removes deleted aggregate views from Redis with bounded asynchronous retries. */
@Service
public class RedisViewCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RedisViewCleanupService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 1000L;

    private final MeterRegistry meterRegistry;
    private final Executor executor;

    /**
     * Creates a cleanup service that records exhausted retries.
     *
     * @param meterRegistry registry used for cleanup failure counters
     */
    public RedisViewCleanupService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Runs a Redis delete action asynchronously and retries transient failures.
     *
     * @param entityLabel entity type attached to logs and metrics
     * @param deleteAction action that removes the Redis view
     * @return a future completed when deletion succeeds or all attempts are exhausted
     */
    public CompletableFuture<Void> cleanupAsync(String entityLabel, Runnable deleteAction) {
        return CompletableFuture.runAsync(
                () -> {
                    int attempt = 0;
                    while (attempt < MAX_ATTEMPTS) {
                        try {
                            deleteAction.run();
                            return; // Success
                        } catch (Exception e) {
                            attempt++;
                            log.warn(
                                    "Failed to delete Redis view for entity {} (Attempt {}/{})",
                                    entityLabel,
                                    attempt,
                                    MAX_ATTEMPTS,
                                    e);
                            if (attempt < MAX_ATTEMPTS) {
                                try {
                                    Thread.sleep(BACKOFF_MS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    }
                    log.error("Exhausted retries cleaning up Redis view for entity {}", entityLabel);
                    Counter.builder("redis_view_cleanup_errors")
                            .tag("entity", entityLabel)
                            .register(meterRegistry)
                            .increment();
                },
                executor);
    }
}
