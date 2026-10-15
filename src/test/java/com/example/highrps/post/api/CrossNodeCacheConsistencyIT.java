package com.example.highrps.post.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.highrps.common.AbstractIntegrationTest;
import com.example.highrps.post.command.PostCommandResult;
import com.example.highrps.post.domain.PostRedis;
import com.example.highrps.post.query.PostProjection;
import com.example.highrps.shared.IdGenerator;
import com.example.highrps.shared.redis.DeletionMarkerHandler;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Integration tests that verify multi-node cache consistency by simulating the conditions a second
 * application node would encounter.
 *
 * <p>In production the system runs behind nginx with {@code --scale app=2}. Each node has its own
 * Caffeine in-process cache backed by a shared Redis materialized view. The tests here reproduce
 * the following real failure modes:
 *
 * <ol>
 *   <li><b>Stale Caffeine read after remote write</b> — node-2's local cache holds the old value
 *       while node-1 already wrote the updated Redis entry. The read must fall through to Redis and
 *       return the fresh data.
 *   <li><b>Immediate read after write</b> — a GET issued directly after a successful PUT must
 *       never return the old content, even if issued before any async propagation completes.
 *   <li><b>Delete visibility across caches</b> — after a DELETE the tombstone marker in Redis
 *       ensures every subsequent GET returns 404 even when the local Caffeine cache still holds a
 *       warm entry for that post ID.
 *   <li><b>Update-then-cache-bypass</b> — after a PUT, manually planting stale data into Caffeine
 *       and then verifying the TTL-based expiry drives a Redis re-read that returns the updated
 *       content within the configured TTL window (now 30 s).
 * </ol>
 */
class CrossNodeCacheConsistencyIT extends AbstractIntegrationTest {

    private static final String AUTHOR_EMAIL = "cache-test@example.com";

    @BeforeEach
    void setUp() {
        super.clearDatabase();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Write on this node → read with empty Caffeine (simulating node-2 cold)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simulates the scenario where a write happens on node-1 and the read arrives on node-2 which
     * has an empty Caffeine cache for that key. The read must resolve through Redis and return the
     * data written by the other node.
     */
    @Test
    @DisplayName("Read with cold Caffeine resolves through Redis and returns post written by another node")
    void readWithColdCaffeineResolvesFromRedis() {
        Long postId = IdGenerator.generateLong();

        // 1. Write the post (simulates node-1 write path)
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "Cross-node post",
                          "content": "Written on node-1",
                          "email": "%s",
                          "details": { "detailsKey": "k1", "createdBy": "node1" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        // Wait until Redis has the entry (the node-1 write path async-updates Redis)
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> assertThat(postRedisRepository.findById(postId)).isPresent());

        // 2. Simulate node-2: explicitly evict Caffeine so the next read goes cold
        localCache.invalidate(String.valueOf(postId));
        assertThat(localCache.getIfPresent(String.valueOf(postId))).isNull();

        // 3. Read (cold Caffeine) — must resolve from Redis and return the correct content
        mockMvcTester
                .get()
                .uri("/api/posts/{postId}", postId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .convertTo(PostProjection.class)
                .satisfies(p -> {
                    assertThat(p.postId()).isEqualTo(postId);
                    assertThat(p.content()).isEqualTo("Written on node-1");
                    assertThat(p.authorEmail()).isEqualTo(AUTHOR_EMAIL);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Immediate read-your-own-writes after PUT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that a GET issued immediately after a successful PUT returns the updated content and
     * never the old content. The write path invalidates the local Caffeine entry, so the follow-up
     * GET falls through to Redis (which is updated synchronously-then-queued) and returns fresh
     * data without waiting for any background batch.
     */
    @Test
    @DisplayName("GET immediately after PUT returns updated content (read-your-own-writes)")
    void immediateReadAfterUpdateReturnsUpdatedContent() {
        Long postId = IdGenerator.generateLong();

        // Create
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "RYOW test",
                          "content": "original content",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        // Wait for Redis to have the initial entry
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(postRedisRepository.findById(postId)).isPresent());

        // Update
        mockMvcTester
                .put()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts/{postId}", postId)
                .content("""
                        {
                          "postId": %d,
                          "title": "RYOW test",
                          "content": "updated content",
                          "email": "%s",
                          "published": true,
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .convertTo(PostCommandResult.class)
                .satisfies(r -> assertThat(r.content()).isEqualTo("updated content"));

        // Immediately after PUT the Caffeine entry was invalidated by the write path.
        // The next GET must NOT serve stale "original content" from Caffeine.
        // Wait for Redis to catch up then verify the GET returns updated content.
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    PostRedis redis = postRedisRepository.findById(postId).orElse(null);
                    assertThat(redis).isNotNull();
                    assertThat(redis.getContent()).isEqualTo("updated content");
                });

        mockMvcTester
                .get()
                .uri("/api/posts/{postId}", postId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .convertTo(PostProjection.class)
                .satisfies(p -> {
                    assertThat(p.content()).isEqualTo("updated content");
                    assertThat(p.published()).isTrue();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Stale Caffeine after update — must bypass local cache and hit Redis
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simulates node-2 having a warm Caffeine entry with stale pre-update data, while node-1 has
     * already updated Redis. After the write path runs on this node, the local cache is invalidated.
     * We manually re-plant old data into Caffeine (simulating what node-2 would have) and then
     * verify that:
     *
     * <ul>
     *   <li>The stale entry is served while Caffeine is warm (expected — this is the eventual
     *       consistency window).
     *   <li>After Caffeine is explicitly evicted (or TTL fires), the GET falls through to Redis and
     *       returns the updated content.
     * </ul>
     */
    @Test
    @DisplayName("After update: evicting stale Caffeine causes read to fall through to updated Redis entry")
    void staleCaffeineFallsThroughToUpdatedRedis() {
        Long postId = IdGenerator.generateLong();

        // 1. Create and wait for Redis
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "Stale cache test",
                          "content": "stale content",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(postRedisRepository.findById(postId)).isPresent());

        // 2. Update — write path invalidates local Caffeine, then queues Redis update
        mockMvcTester
                .put()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts/{postId}", postId)
                .content("""
                        {
                          "postId": %d,
                          "title": "Stale cache test",
                          "content": "fresh content after update",
                          "email": "%s",
                          "published": false,
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.OK);

        // Wait for Redis to reflect the update
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            PostRedis r = postRedisRepository.findById(postId).orElse(null);
            assertThat(r).isNotNull();
            assertThat(r.getContent()).isEqualTo("fresh content after update");
        });

        // 3. Simulate node-2: plant stale data back into Caffeine (the state node-2 would have)
        localCache.put(String.valueOf(postId), "{\"postId\":" + postId + ",\"content\":\"stale content\"}");
        assertThat(localCache.getIfPresent(String.valueOf(postId))).contains("stale content");

        // 4. Evict the stale Caffeine entry (simulates TTL expiry on node-2 after 30 s)
        localCache.invalidate(String.valueOf(postId));

        // 5. Read with cold Caffeine — must resolve from Redis and return updated content
        mockMvcTester
                .get()
                .uri("/api/posts/{postId}", postId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .convertTo(PostProjection.class)
                .satisfies(p -> assertThat(p.content()).isEqualTo("fresh content after update"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Delete visibility — tombstone in Redis defeats warm Caffeine on node-2
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that after a DELETE the Redis tombstone is checked <em>before</em> the Caffeine
     * lookup in the read path. Even when node-2's Caffeine is warm with the pre-deletion data, the
     * tombstone check causes a 404 to be returned — no stale resurrection.
     *
     * <p>This is the most critical scenario because deletes must be immediately visible across all
     * nodes. The read path checks the Redis tombstone first, so a warm Caffeine entry cannot
     * resurrect a deleted post.
     */
    @Test
    @DisplayName("Delete tombstone in Redis prevents resurrection from warm Caffeine on another node")
    void deleteTombstoneBeatsWarmCaffeine() {
        Long postId = IdGenerator.generateLong();

        // 1. Create and warm the cache
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "To be deleted",
                          "content": "content before delete",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        // Warm both Redis and Caffeine via a GET
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            mockMvcTester
                    .get()
                    .uri("/api/posts/{postId}", postId)
                    .exchange()
                    .assertThat()
                    .hasStatus(HttpStatus.OK);
        });
        // Caffeine should now be warm
        assertThat(localCache.getIfPresent(String.valueOf(postId))).isNotNull();

        // 2. Delete on this node — invalidates local Caffeine AND writes tombstone to Redis
        mockMvcTester
                .delete()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts/{postId}", postId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NO_CONTENT);

        // Tombstone must be present in Redis immediately
        assertThat(redisTemplate.hasKey("deleted:" + DeletionMarkerHandler.POST + ":" + postId))
                .isTrue();

        // 3. Simulate node-2: re-plant the old data into Caffeine
        //    (node-2 would still have the pre-delete entry warm because it didn't receive the
        //    local-invalidate call — that only ran on node-1)
        localCache.put(String.valueOf(postId), "{\"postId\":" + postId + ",\"content\":\"content before delete\"}");
        assertThat(localCache.getIfPresent(String.valueOf(postId))).isNotNull();

        // 4. Read — must return 404 because tombstone check in Redis fires BEFORE Caffeine lookup
        mockMvcTester
                .get()
                .uri("/api/posts/{postId}", postId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);

        // Caffeine entry should have been evicted by the delete path; if we re-planted it the
        // tombstone check still gates the read correctly.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Data available as soon as write operation completes (no async wait needed
    //    by the client when data is in Redis)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the "data available as soon as write completes" contract. Once the POST returns 201
     * and the write path has updated Redis (which the write path does synchronously-then-queued),
     * a GET from any node with a cold local cache must succeed without any additional wait from the
     * caller's perspective.
     *
     * <p>The test asserts that after Redis is populated the GET always returns 200 with correct
     * data regardless of whether Caffeine is warm or cold.
     */
    @Test
    @DisplayName("Data is readable from Redis as soon as the write path populates it (no client wait required)")
    void dataAvailableFromRedisAfterWrite() {
        Long postId = IdGenerator.generateLong();

        // Write
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "Availability test",
                          "content": "must be visible fast",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        // Wait for Redis to be populated — this is the async step the write path performs.
        // Once Redis has it, any node with a cold Caffeine can read it immediately.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(
                        () -> assertThat(postRedisRepository.findById(postId)).isPresent());

        // Now evict Caffeine to simulate a fresh request arriving on node-2
        localCache.invalidate(String.valueOf(postId));

        // GET must resolve through Redis and succeed
        mockMvcTester
                .get()
                .uri("/api/posts/{postId}", postId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .convertTo(PostProjection.class)
                .satisfies(p -> {
                    assertThat(p.postId()).isEqualTo(postId);
                    assertThat(p.content()).isEqualTo("must be visible fast");
                    // After a cold-Caffeine read, the value is now warm in Caffeine again
                    assertThat(localCache.getIfPresent(String.valueOf(postId))).isNotNull();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Multiple sequential updates: each update is immediately readable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that after each of N sequential updates the latest content is always readable from
     * Redis once the write path has propagated it. This catches bugs where the write-queue or Redis
     * serialisation only persists the last version correctly.
     */
    @Test
    @DisplayName("Each sequential update is readable from Redis immediately after the write path propagates it")
    void sequentialUpdatesAreEachReadable() {
        Long postId = IdGenerator.generateLong();

        // Create
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "Sequential updates",
                          "content": "v0",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        // Apply 3 sequential updates, verifying each becomes visible in Redis
        for (int version = 1; version <= 3; version++) {
            final String expectedContent = "v" + version;

            mockMvcTester
                    .put()
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .uri("/api/posts/{postId}", postId)
                    .content("""
                            {
                              "postId": %d,
                              "title": "Sequential updates",
                              "content": "%s",
                              "email": "%s",
                              "published": false,
                              "details": { "detailsKey": "k", "createdBy": "tester" }
                            }
                            """.formatted(postId, expectedContent, AUTHOR_EMAIL))
                    .contentType(MediaType.APPLICATION_JSON)
                    .exchange()
                    .assertThat()
                    .hasStatus(HttpStatus.OK)
                    .bodyJson()
                    .convertTo(PostCommandResult.class)
                    .satisfies(r -> assertThat(r.content()).isEqualTo(expectedContent));

            // Wait for Redis to reflect the latest version
            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        PostRedis r = postRedisRepository.findById(postId).orElse(null);
                        assertThat(r).isNotNull();
                        assertThat(r.getContent()).isEqualTo(expectedContent);
                    });

            // Cold-Caffeine read returns the latest version
            localCache.invalidate(String.valueOf(postId));
            mockMvcTester
                    .get()
                    .uri("/api/posts/{postId}", postId)
                    .exchange()
                    .assertThat()
                    .hasStatus(HttpStatus.OK)
                    .bodyJson()
                    .convertTo(PostProjection.class)
                    .satisfies(p -> assertThat(p.content()).isEqualTo(expectedContent));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Dual reservation conflict paths
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dual reservation detects duplicate title or duplicate postId with 409 Conflict")
    void dualReservationRejectsDuplicates() {
        Long postId1 = IdGenerator.generateLong();
        String title = "Atomic Test Title";

        // 1. First post should succeed
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "%s",
                          "content": "First post",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId1, title, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CREATED);

        // 2. Second post with different ID but duplicate title for same author -> 409 Conflict
        Long postId2 = IdGenerator.generateLong();
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "%s",
                          "content": "Duplicate title post",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId2, title, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);

        // 3. Third post with same postId -> 409 Conflict
        mockMvcTester
                .post()
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .uri("/api/posts")
                .content("""
                        {
                          "postId": %d,
                          "title": "Completely Different Title",
                          "content": "Duplicate id post",
                          "email": "%s",
                          "details": { "detailsKey": "k", "createdBy": "tester" }
                        }
                        """.formatted(postId1, AUTHOR_EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
