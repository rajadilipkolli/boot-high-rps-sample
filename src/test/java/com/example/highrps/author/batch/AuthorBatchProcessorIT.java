package com.example.highrps.author.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.common.AbstractIntegrationTest;
import com.example.highrps.infrastructure.batch.ScheduledBatchProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorBatchProcessorIT extends AbstractIntegrationTest {

    @BeforeEach
    void setUp() {
        super.clearDatabase();
    }

    @Test
    void processUpserts_insertsNewAuthors_and_updatesExisting() {
        String payload1 = """
                {"firstName":"John","middleName":"M","lastName":"Doe","mobile":1234567890,"email":"john@example.com"}
                """;
        String payload2 = """
                {"firstName":"Jane","middleName":"A","lastName":"Smith","mobile":9876543210,"email":"jane@example.com"}
                """;

        authorBatchProcessor.processUpserts(List.of(payload1, payload2));

        List<AuthorEntity> all = authorRepository.findAll();
        assertThat(all).hasSize(2);

        Map<String, AuthorEntity> byEmail =
                all.stream().collect(Collectors.toMap(a -> a.getEmail().toLowerCase(), a -> a));

        assertThat(byEmail).containsKeys("john@example.com", "jane@example.com");
        assertThat(byEmail.get("john@example.com").getFirstName()).isEqualTo("John");

        // Now update John
        String updateJohn = """
                {"firstName":"Johnny","middleName":"M","lastName":"Doe","mobile":1234567890,"email":"john@example.com"}
                """;
        authorBatchProcessor.processUpserts(List.of(updateJohn));

        AuthorEntity updated = authorRepository.findAll().stream()
                .filter(a -> "john@example.com".equalsIgnoreCase(a.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(updated.getFirstName()).isEqualTo("Johnny");
    }

    @Test
    void processUpserts_skipsWhenTombstoneExists() {
        String email = "tomb@example.com";
        String payload = """
                {"firstName":"Tomb","middleName":null,"lastName":"Stone","mobile":1111111111,"email":"%s"}
                """.formatted(email);

        // mark tombstone in Redis with unified key format
        redisTemplate.opsForValue().set("deleted:author:" + email, "1", Duration.ofSeconds(60));

        authorBatchProcessor.processUpserts(List.of(payload));

        boolean exists = authorRepository.existsByEmailIgnoreCase(email);
        assertThat(exists).isFalse();
    }

    @Test
    void processDeletes_removesAuthors() {
        // create authors
        AuthorEntity a1 = new AuthorEntity()
                .setFirstName("A")
                .setLastName("LA")
                .setMobile(9876543210L)
                .setEmail("d1@example.com");
        AuthorEntity a2 = new AuthorEntity()
                .setFirstName("B")
                .setLastName("LB")
                .setMobile(9087654321L)
                .setEmail("d2@example.com");
        authorRepository.saveAll(List.of(a1, a2));

        assertThat(authorRepository.findAll()).hasSize(2);

        authorBatchProcessor.processDeletes(List.of("d1@example.com", "D2@Example.com"));

        assertThat(authorRepository.findAll()).isEmpty();
    }

    /**
     * Reproduces concurrent duplicate writes for the same email.
     * Two threads call processUpserts simultaneously for one new email.
     * Asserts exactly one persisted author row and no exception leaking out.
     */
    @Test
    void processUpserts_concurrentWritesSameEmail_resultsInExactlyOneRow() throws InterruptedException {
        String email = "concurrent@example.com";
        String payload = """
                {"firstName":"Concurrent","middleName":null,"lastName":"User","mobile":5555555555,"email":"%s"}
                """.formatted(email);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        Throwable[] errors = new Throwable[2];

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // wait for both threads to be ready
                        authorBatchProcessor.processUpserts(List.of(payload));
                    } catch (Throwable t) {
                        errors[idx] = t;
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release both threads simultaneously
            startLatch.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // No exception should have leaked out
        assertThat(errors[0]).isNull();
        assertThat(errors[1]).isNull();

        // Exactly one row must exist
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(authorRepository.findAll().stream()
                                .filter(a -> email.equalsIgnoreCase(a.getEmail()))
                                .count())
                        .isEqualTo(1));
    }

    /**
     * Tests different payloads for the same email processed via the scheduler.
     * The scheduler deduplicates by key, so only one payload reaches processUpserts.
     * Asserts exactly one persisted row and uses the scheduledBatchProcessors pattern.
     */
    @Test
    void processUpserts_viaScheduler_deduplicatesSameEmail() {
        String email = "scheduled@example.com";
        String payload1 = """
                {"firstName":"First","middleName":null,"lastName":"User","mobile":1111111111,"email":"%s","__entity":"author"}
                """.formatted(email);
        String payload2 = """
                {"firstName":"Second","middleName":null,"lastName":"User","mobile":2222222222,"email":"%s","__entity":"author"}
                """.formatted(email);

        // Enqueue both payloads to the stream
        String queueKey = "events:queue";
        redisTemplate.opsForStream().add(queueKey, Map.of("payload", payload1));
        redisTemplate.opsForStream().add(queueKey, Map.of("payload", payload2));

        // Trigger batch processing via the scheduler pattern
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    scheduledBatchProcessors.forEach(ScheduledBatchProcessor::processBatch);
                    assertThat(authorRepository.findAll().stream()
                                    .filter(a -> email.equalsIgnoreCase(a.getEmail()))
                                    .count())
                            .isEqualTo(1);
                });
    }
}
