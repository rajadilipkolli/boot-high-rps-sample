package com.example.highrps.shared;

import com.example.highrps.shared.config.AppProperties;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base template class for command services.
 * Encapsulates the asynchronous pipeline for publishing domain events
 * to Redis Streams and subsequently locking the aggregate to perform cache updates or tombstoning.
 */
public abstract class AbstractCommandService {

    private static final Logger log = LoggerFactory.getLogger(AbstractCommandService.class);
    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    protected final RedisTemplate<String, String> redisTemplate;
    protected final JsonMapper jsonMapper;
    protected final String queueKey;
    protected final AggregateOperationQueue operationQueue;

    protected AbstractCommandService(
            RedisTemplate<String, String> redisTemplate, JsonMapper jsonMapper, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.queueKey = appProperties.getBatch().getQueueKey();
        this.operationQueue = new AggregateOperationQueue();
    }

    /**
     * Executes a command pipeline.
     */
    protected <T> CompletableFuture<T> executeCommand(
            String entityType,
            String aggregateKey,
            String lockKey,
            Object event,
            T result,
            Runnable postPublishAction,
            String actionLogName,
            String entityLogName) {

        CompletableFuture<Void> sendFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        String jsonString = jsonMapper.writeValueAsString(event);
                        if (jsonString.startsWith("{")) {
                            jsonString = "{\"__entity\":\"" + entityType + "\"," + jsonString.substring(1);
                        }
                        redisTemplate.opsForStream().add(queueKey, Collections.singletonMap("payload", jsonString));
                        log.info("Successfully published {} event for key: {}", actionLogName, aggregateKey);
                        return null;
                    } catch (Exception e) {
                        log.error("Failed to publish {} event for key: {}", actionLogName, aggregateKey, e);
                        throw new RuntimeException("Failed to publish event", e);
                    }
                },
                VIRTUAL_EXECUTOR);

        return sendFuture.thenComposeAsync(
                ignored -> operationQueue
                        .enqueue(lockKey, () -> {
                            try {
                                postPublishAction.run();
                                log.info("{} {} successfully: {}", entityLogName, actionLogName, lockKey);
                            } catch (Exception e) {
                                log.warn(
                                        "Post-publish action failed for {} {}, but event was published.",
                                        entityLogName,
                                        actionLogName,
                                        e);
                            }
                            return CompletableFuture.completedFuture(null);
                        })
                        .thenApply(v -> result),
                VIRTUAL_EXECUTOR);
    }

    protected boolean isPendingPublishFailure(Throwable err) {
        return false;
    }
}
