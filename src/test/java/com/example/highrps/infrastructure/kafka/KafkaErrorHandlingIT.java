package com.example.highrps.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.highrps.common.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class KafkaErrorHandlingIT extends AbstractIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        super.clearDatabase();
    }

    @AfterEach
    void resetFaultInjectionState() {
        circuitBreakerRegistry.circuitBreaker("redisProjection").reset();
    }

    /** Verifies that malformed aggregate records are routed to the dead-letter topic and Redis. */
    @Test
    @DisplayName("Should route poison pill from consumer to DLT and save to Redis")
    void shouldRoutePoisonPillToDLT() {
        // Arrange
        String topic = "posts-aggregates";
        String retryableTopicDlt = topic + "-dlt";
        String dlqKey = "dlq:" + retryableTopicDlt;

        // Act: send poison pill
        String poisonPillKey = "poison-pill-key";
        byte[] poisonPillValue = "invalid-json-not-base64".getBytes();
        try {
            kafkaTemplate.send(topic, poisonPillKey, poisonPillValue).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Assert: verify the poison pill arrives in DLT and is written to Redis by @DltHandler
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long size = redisTemplate.opsForList().size(dlqKey);
            assertThat(size).isNotNull().isGreaterThanOrEqualTo(1L);
        });
    }
}
