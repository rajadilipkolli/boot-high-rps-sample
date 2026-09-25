package com.example.highrps.postcomment.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.common.AbstractIntegrationTest;
import com.example.highrps.post.domain.PostEntity;
import com.example.highrps.postcomment.domain.PostCommentEntity;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PostCommentBatchProcessorIT extends AbstractIntegrationTest {

    @Autowired
    private PostCommentBatchProcessor postCommentBatchProcessor;

    private PostEntity testPost;

    @BeforeEach
    void setUp() {
        super.clearDatabase();

        // Create a test author and post required by comment upserts
        AuthorEntity author = new AuthorEntity()
                .setFirstName("Comment")
                .setLastName("Tester")
                .setMobile(8888888888L)
                .setEmail("comment-tester@example.com");
        author.setCreatedAt(LocalDateTime.now());
        authorRepository.save(author);

        testPost = new PostEntity("Test Post For Comments", "Post content", author);
        testPost.setPostRefId(200001L);
        testPost.setVersion((short) 0);
        testPost.setCreatedAt(LocalDateTime.now());
        testPost.setModifiedAt(LocalDateTime.now());

        com.example.highrps.post.domain.PostDetailsEntity details =
                new com.example.highrps.post.domain.PostDetailsEntity()
                        .setDetailsKey("test-details")
                        .setCreatedBy("tester");
        details.setVersion((short) 0);
        details.setCreatedAt(LocalDateTime.now());
        details.setModifiedAt(LocalDateTime.now());
        testPost.setDetails(details);

        postRepository.save(testPost);
    }

    @Test
    void processUpserts_insertsNewComment() {
        long commentId = 300001L;
        String payload = buildCommentPayload(commentId, testPost.getPostRefId(), "First Comment");

        postCommentBatchProcessor.processUpserts(List.of(payload));

        List<PostCommentEntity> comments = postCommentRepository.findAll();
        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().getCommentRefId()).isEqualTo(commentId);
        assertThat(comments.getFirst().getTitle()).isEqualTo("First Comment");
    }

    @Test
    void processUpserts_skipsCommentWhenParentPostMissing() {
        long commentId = 300002L;
        long missingPostId = 999999L;
        String payload = buildCommentPayload(commentId, missingPostId, "Orphan Comment");

        postCommentBatchProcessor.processUpserts(List.of(payload));

        assertThat(postCommentRepository.findAll()).isEmpty();
    }

    /**
     * Reproduces concurrent duplicate writes for the same commentRefId.
     * Two threads call processUpserts simultaneously for one new comment.
     * Asserts exactly one persisted row and no unhandled exception.
     */
    @Test
    void processUpserts_concurrentWritesSameCommentRefId_resultsInExactlyOneRow() throws InterruptedException {
        long commentId = 300003L;
        String payload = buildCommentPayload(commentId, testPost.getPostRefId(), "Concurrent Comment");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        Throwable[] errors = new Throwable[2];

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        postCommentBatchProcessor.processUpserts(List.of(payload));
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
                .untilAsserted(() -> assertThat(postCommentRepository.findByCommentRefIdIn(List.of(commentId)))
                        .hasSize(1));
    }

    // ── Payload builder ───────────────────────────────────────────────────────

    private String buildCommentPayload(long commentId, long postId, String title) {
        return """
                {"id":%d,"postId":%d,"title":"%s","content":"Comment content",
                 "published":false,"publishedAt":null,
                 "createdAt":"2026-01-01T00:00:00","modifiedAt":"2026-01-01T00:00:00",
                 "__entity":"post-comment"}
                """.formatted(commentId, postId, title);
    }
}
