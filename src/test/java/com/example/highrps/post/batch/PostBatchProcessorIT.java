package com.example.highrps.post.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.common.AbstractIntegrationTest;
import com.example.highrps.infrastructure.batch.ScheduledBatchProcessor;
import com.example.highrps.post.domain.PostEntity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

class PostBatchProcessorIT extends AbstractIntegrationTest {

    @Autowired
    private PostBatchProcessor postBatchProcessor;

    @BeforeEach
    void setUp() {
        super.clearDatabase();
        // Create a test author required by post upserts
        AuthorEntity testAuthor = new AuthorEntity()
                .setFirstName("Post")
                .setLastName("Tester")
                .setMobile(9999999999L)
                .setEmail("post-tester@example.com");
        authorRepository.save(testAuthor);
    }

    @Test
    void processUpserts_insertsNewPost_andPersistsDetails() {
        long postId = 100001L;
        String payload = buildPostPayload(postId, "Conflict Safe Post", "post-tester@example.com");

        postBatchProcessor.processUpserts(List.of(payload));

        List<PostEntity> posts = postRepository.findByPostRefIdIn(List.of(postId));
        assertThat(posts).hasSize(1);
        PostEntity post = posts.getFirst();
        assertThat(post.getPostRefId()).isEqualTo(postId);
        assertThat(post.getTitle()).isEqualTo("Conflict Safe Post");
        assertThat(post.getDetails()).isNotNull();
        assertThat(post.getDetails().getDetailsKey()).isEqualTo("details-key-1");
    }

    @Test
    void processUpserts_noDuplicatePostTagRows() {
        long postId = 100002L;
        String payload = buildPostPayloadWithTags(postId, "Tagged Post", "post-tester@example.com");

        postBatchProcessor.processUpserts(List.of(payload));

        List<PostEntity> posts = postRepository.findByPostRefIdIn(List.of(postId));
        assertThat(posts).hasSize(1);
        assertThat(postTagRepository.findAll()).hasSizeLessThanOrEqualTo(2);
    }

    /**
     * Reproduces concurrent duplicate writes for the same postRefId.
     * Two threads call processUpserts simultaneously for one new post.
     * Asserts exactly one persisted row and no unhandled exception.
     */
    @Test
    void processUpserts_concurrentWritesSamePostRefId_resultsInExactlyOneRow() throws InterruptedException {
        long postId = 100003L;
        String payload = buildPostPayload(postId, "Concurrent Post", "post-tester@example.com");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        Throwable[] errors = new Throwable[2];

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        postBatchProcessor.processUpserts(List.of(payload));
                    } catch (Throwable t) {
                        errors[idx] = t;
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // No exception should have leaked out
        assertThat(errors[0]).isNull();
        assertThat(errors[1]).isNull();

        // Exactly one row must exist
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(postRepository.findByPostRefIdIn(List.of(postId)))
                        .hasSize(1));
    }

    /**
     * Tests different postId values with the same title and author.
     * The scheduler should produce one persisted row (first postId wins the title reservation)
     * and a DLQ record with the conflict reason for the second postId.
     */
    @Test
    void processUpserts_differentPostIdsSameTitleAndAuthor_onePersisted_oneDlq() {
        long postId1 = 100010L;
        long postId2 = 100011L;
        String title = "Duplicate Title By Author";
        String authorEmail = "post-tester@example.com";

        String payload1 = buildNamedPostPayload(postId1, title, authorEmail);
        String payload2 = buildNamedPostPayload(postId2, title, authorEmail);

        // Enqueue both payloads – they have different postRefIds so the scheduler
        // does NOT deduplicate them; both flow through to individual upsert calls.
        String queueKey = "events:queue";
        redisTemplate.opsForStream().add(queueKey, Map.of("payload", payload1));
        redisTemplate.opsForStream().add(queueKey, Map.of("payload", payload2));

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    scheduledBatchProcessors.forEach(ScheduledBatchProcessor::processBatch);

                    // Exactly one post row with that title/author combination
                    long count = postRepository.findAll().stream()
                            .filter(p -> title.equals(p.getTitle()))
                            .count();
                    assertThat(count).isEqualTo(1);

                    // At least one DLQ entry with unique_constraint_conflict reason
                    List<MapRecord<String, Object, Object>> dlqEntries = redisTemplate
                            .opsForStream()
                            .read(StreamReadOptions.empty().count(100), StreamOffset.fromStart("events:dlq"));

                    boolean hasDlqConflict = dlqEntries != null
                            && dlqEntries.stream().anyMatch(r -> {
                                Object reason = r.getValue().get("reason");
                                return reason != null && reason.toString().contains("unique_constraint_conflict");
                            });
                    assertThat(hasDlqConflict).isTrue();
                });
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private String buildPostPayload(long postId, String title, String authorEmail) {
        return """
                {"postId":%d,"title":"%s","content":"content","email":"%s","published":false,
                 "details":{"detailsKey":"details-key-1","createdBy":"test"},"__entity":"post"}
                """.formatted(postId, title, authorEmail);
    }

    private String buildPostPayloadWithTags(long postId, String title, String authorEmail) {
        return """
                {"postId":%d,"title":"%s","content":"content","email":"%s","published":false,
                 "details":{"detailsKey":"details-key-2","createdBy":"test"},
                 "tags":[{"tagName":"java","tagDescription":"Java language"},
                         {"tagName":"spring","tagDescription":"Spring framework"}],
                 "__entity":"post"}
                """.formatted(postId, title, authorEmail);
    }

    private String buildNamedPostPayload(long postId, String title, String authorEmail) {
        return """
                {"postId":%d,"title":"%s","content":"content-%d","email":"%s","published":false,
                 "details":{"detailsKey":"details-%d","createdBy":"test"},"__entity":"post"}
                """.formatted(postId, title, postId, authorEmail, postId);
    }
}
