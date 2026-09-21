package com.example.highrps.shared;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A queue that serializes aggregate operations by key using CompletableFutures.
 * Used to ensure that operations for a specific aggregate ID execute sequentially.
 */
public class AggregateOperationQueue {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
    private final Executor executor;
    private final MeterRegistry meterRegistry;

    public AggregateOperationQueue() {
        this(null);
    }

    public AggregateOperationQueue(MeterRegistry meterRegistry) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.meterRegistry = meterRegistry;
    }

    public CompletableFuture<Void> enqueue(String aggregateKey, Supplier<CompletableFuture<Void>> operation) {
        AtomicReference<CompletableFuture<Void>> currentRef = new AtomicReference<>();
        long enqueueTime = System.nanoTime();

        futures.compute(aggregateKey, (key, previous) -> {
            CompletableFuture<Void> tail = (previous == null) ? CompletableFuture.completedFuture(null) : previous;

            CompletableFuture<Void> next = tail.exceptionally(ex -> null)
                    .thenComposeAsync(
                            ignored -> {
                                if (meterRegistry != null) {
                                    Timer.builder("redis_write_queue_wait")
                                            .register(meterRegistry)
                                            .record(java.time.Duration.ofNanos(System.nanoTime() - enqueueTime));
                                }
                                return operation.get();
                            },
                            executor);

            currentRef.set(next);
            return next;
        });

        CompletableFuture<Void> current = currentRef.get();

        current.whenComplete((ignored, throwable) -> {
            futures.computeIfPresent(aggregateKey, (key, tail) -> tail == current ? null : tail);
        });

        return current;
    }
}
