package com.example.highrps.post.batch;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.author.domain.AuthorRepository;
import com.example.highrps.infrastructure.batch.EntityBatchProcessor;
import com.example.highrps.infrastructure.batch.UniqueConstraintConflictException;
import com.example.highrps.post.domain.PostEntity;
import com.example.highrps.post.domain.PostRepository;
import com.example.highrps.post.domain.TagEntity;
import com.example.highrps.post.domain.TagRepository;
import com.example.highrps.post.domain.TagResponse;
import com.example.highrps.post.domain.requests.NewPostRequest;
import com.example.highrps.post.mapper.NewPostRequestToPostEntityMapper;
import com.example.highrps.shared.redis.DeletionMarkerHandler;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PostBatchProcessor implements EntityBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(PostBatchProcessor.class);

    private final NewPostRequestToPostEntityMapper mapper;
    private final PostRepository postRepository;
    private final TagRepository tagRepository;
    private final JsonMapper jsonMapper;
    private final AuthorRepository authorRepository;
    private final DeletionMarkerHandler deletionMarkerHandler;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public PostBatchProcessor(
            NewPostRequestToPostEntityMapper mapper,
            PostRepository postRepository,
            TagRepository tagRepository,
            JsonMapper jsonMapper,
            AuthorRepository authorRepository,
            DeletionMarkerHandler deletionMarkerHandler,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.postRepository = postRepository;
        this.tagRepository = tagRepository;
        this.jsonMapper = jsonMapper;
        this.authorRepository = authorRepository;
        this.deletionMarkerHandler = deletionMarkerHandler;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public String getEntityType() {
        return "post";
    }

    /**
     * Applies the last payload for each post ID, creating or updating posts with an existing author.
     * Payloads with a deletion marker, invalid JSON, or no post ID are skipped, as are posts whose
     * authors cannot be found. Database and mapping failures propagate and roll back the batch.
     *
     * @param payloads serialized post requests
     * @throws NumberFormatException if a post with an existing author has a nonnumeric ID
     * @throws UniqueConstraintConflictException if a candidate post is absent after insertion and refetch
     */
    @Override
    public void processUpserts(List<String> payloads) {
        // ── Phase 1 (no transaction): parse payloads + tombstone filter ──────────
        // Redis calls and JSON parsing happen outside any DB transaction so we don't
        // hold a connection while doing non-DB work.
        List<ParsedPost> parsedPosts = payloads.stream()
                .map(payload -> {
                    String postId = extractKey(payload);
                    if (postId != null) {
                        // Skip if tombstone exists
                        if (deletionMarkerHandler.isDeleted("post", postId)) {
                            log.debug("Skipping upsert for postId {} because recent tombstone present", postId);
                            return null;
                        }
                    }
                    try {
                        NewPostRequest req = jsonMapper.readValue(payload, NewPostRequest.class);
                        return new ParsedPost(postId, req);
                    } catch (Exception e) {
                        log.warn("Failed to map post payload to entity: {}", payload, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(p -> p.postId() != null)
                .collect(Collectors.toMap(ParsedPost::postId, Function.identity(), (a, b) -> b))
                .values()
                .stream()
                .toList();

        if (parsedPosts.isEmpty()) {
            return;
        }

        // ── Phase 2 (transaction): tag upsert → native insert → locked refetch → saveAll ─
        // Transaction is opened here — only wraps actual DB I/O.
        transactionTemplate.executeWithoutResult(status -> {
            // Step 2a: Bulk fetch authors (preserve author lookup and the skip for a missing author)
            List<String> emails = parsedPosts.stream()
                    .map(p -> p.request().email())
                    .filter(Objects::nonNull)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();

            Map<String, AuthorEntity> authorByEmail = authorRepository.findByEmailInAllIgnoreCase(emails).stream()
                    .collect(Collectors.toMap(
                            a -> a.getEmail().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));

            // Step 2b: Bulk fetch + idempotent-insert tags (preserve tag handling)
            Map<String, TagResponse> tagRequestsByName = parsedPosts.stream()
                    .flatMap(p -> {
                        var tags = p.request().tags();
                        return tags != null ? tags.stream() : Stream.<TagResponse>empty();
                    })
                    .filter(t ->
                            t != null && t.tagName() != null && !t.tagName().isBlank())
                    .collect(Collectors.toMap(
                            t -> t.tagName().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));

            List<String> tagNames = new ArrayList<>(tagRequestsByName.keySet());

            Map<String, TagEntity> tagMap = new HashMap<>();
            if (!tagNames.isEmpty()) {
                List<TagEntity> existingTags = tagRepository.findByTagNameInAllIgnoreCase(tagNames);
                existingTags.forEach(t -> tagMap.put(t.getTagName().toLowerCase(Locale.ROOT), t));

                List<TagEntity> newTags = tagNames.stream()
                        .filter(name -> !tagMap.containsKey(name))
                        .map(name -> {
                            TagResponse tr = tagRequestsByName.get(name);
                            TagEntity tagEntity =
                                    new TagEntity().setTagName(tr.tagName()).setTagDescription(tr.tagDescription());
                            tagEntity.setCreatedAt(LocalDateTime.now());
                            return tagEntity;
                        })
                        .toList();

                if (!newTags.isEmpty()) {
                    String sql = "INSERT INTO tags (id, tag_name, tag_description, version, created_at) "
                            + "VALUES (nextval('tags_seq'), ?, ?, 0, CURRENT_TIMESTAMP) "
                            + "ON CONFLICT (lower(tag_name)) DO NOTHING";

                    jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            TagEntity t = newTags.get(i);
                            ps.setString(1, t.getTagName());
                            ps.setString(2, t.getTagDescription());
                        }

                        @Override
                        public int getBatchSize() {
                            return newTags.size();
                        }
                    });

                    // Refetch to get IDs for all tags, including newly natively inserted ones
                    List<TagEntity> refetchedTags = tagRepository.findByTagNameInAllIgnoreCase(tagNames);
                    refetchedTags.forEach(t -> tagMap.put(t.getTagName().toLowerCase(Locale.ROOT), t));
                }
            }

            // Step 2c: Identify candidates: only those with a valid author get an insert attempt.
            //          Skip (return null) for missing author, matching the existing behaviour.
            List<ParsedPost> candidates = parsedPosts.stream()
                    .filter(p -> {
                        String authorEmail = p.request().email();
                        if (authorEmail == null || !authorByEmail.containsKey(authorEmail.toLowerCase(Locale.ROOT))) {
                            log.warn("Author not found for email {}, rejecting post {}", authorEmail, p.postId());
                            return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            // Sort by postRefId to guarantee consistent lock-acquisition order.
            candidates.sort(Comparator.comparing(p -> Long.valueOf(p.postId())));

            List<Long> candidatePostIds =
                    candidates.stream().map(p -> Long.valueOf(p.postId())).collect(Collectors.toList());

            if (candidatePostIds.isEmpty()) {
                return;
            }

            // Step 2d: Try to insert genuinely new rows into both posts and post_details.
            //          We use a writable CTE to insert into posts and pass the generated ID to post_details.
            String insertSql = "WITH ins_post AS ("
                    + "  INSERT INTO posts (id, post_ref_id, title, content, published, published_at, author_id, version, created_at, modified_at) "
                    + "  VALUES (nextval('posts_seq'), ?, ?, ?, ?, ?, (SELECT id FROM authors WHERE lower(email) = lower(?)), 0, now(), now()) "
                    + "  ON CONFLICT (post_ref_id) DO NOTHING RETURNING id, created_at, modified_at"
                    + ") "
                    + "INSERT INTO post_details (id, details_key, created_by, version, created_at, modified_at) "
                    + "SELECT id, ?, ?, 0, created_at, modified_at FROM ins_post";

            jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ParsedPost p = candidates.get(i);
                    NewPostRequest req = p.request();
                    // Parameters for posts
                    ps.setLong(1, Long.parseLong(p.postId()));
                    ps.setString(2, req.title());
                    ps.setString(3, req.content());
                    ps.setBoolean(4, Boolean.TRUE.equals(req.published()));
                    if (req.publishedAt() != null) {
                        ps.setObject(5, req.publishedAt());
                    } else {
                        ps.setNull(5, java.sql.Types.TIMESTAMP);
                    }
                    ps.setString(6, req.email());
                    // Parameters for post_details
                    if (req.details() != null) {
                        ps.setString(7, req.details().detailsKey());
                        ps.setString(8, req.details().createdBy());
                    } else {
                        ps.setNull(7, java.sql.Types.VARCHAR);
                        ps.setNull(8, java.sql.Types.VARCHAR);
                    }
                }

                @Override
                public int getBatchSize() {
                    return candidates.size();
                }
            });

            // Step 2e: Refetch under pessimistic write lock.
            List<PostEntity> lockedPosts = postRepository.findByPostRefIdInWithLock(candidatePostIds);

            Map<Long, PostEntity> lockedByPostId = lockedPosts.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(PostEntity::getPostRefId, Function.identity(), (e1, e2) -> e1));

            // Step 2f: Assemble entities — apply details + tag associations via JPA mapper.
            List<PostEntity> entitiesToSave = new ArrayList<>();
            for (ParsedPost parsed : candidates) {
                Long postId = Long.valueOf(parsed.postId());
                PostEntity entity = lockedByPostId.get(postId);

                if (entity == null) {
                    // A valid-author payload has no row after refetch → concurrent conflict.
                    // Throw Phase-1 conflict exception so the scheduler can retry individually.
                    throw new UniqueConstraintConflictException(
                            "post", "uc_postentity_post_ref_id", List.of(parsed.postId()));
                }

                try {
                    mapper.updatePostEntity(parsed.request(), entity, tagMap);
                    log.debug("Upserting post with postRefId: {}", parsed.postId());
                    entitiesToSave.add(entity);
                } catch (Exception e) {
                    log.error("Failed to update post entity for postId: {}", parsed.postId(), e);
                    throw new RuntimeException("Failed to update post entity for postId: " + parsed.postId(), e);
                }
            }

            if (!entitiesToSave.isEmpty()) {
                try {
                    postRepository.saveAll(entitiesToSave);
                    log.debug("Persisted batch of {} post entities", entitiesToSave.size());
                } catch (Exception e) {
                    log.error("Failed to persist batch of {} post entities", entitiesToSave.size(), e);
                    throw e;
                }
            }
        });
    }

    @Override
    public void processDeletes(List<String> keys) {
        if (keys.isEmpty()) return;
        try {
            long deletedRows = postRepository.deleteByPostRefIdIn(
                    keys.stream().map(Long::valueOf).toList());
            log.debug("Deleted rows :{} post entity for keys :{}", deletedRows, keys);
        } catch (Exception e) {
            log.warn("Failed to batch delete post entities for keys: {}", keys, e);
        }
    }

    @Override
    public String extractKey(String payload) {
        try {
            var node = jsonMapper.readTree(payload);
            String postId = node.path("postId").asString(null);
            if (postId == null || postId.isBlank()) {
                log.warn("Missing 'postId' field in post payload: {}", payload);
                return null;
            }
            return postId;
        } catch (Exception e) {
            log.warn("Failed to extract postId from post payload", e);
            return null;
        }
    }

    // Helper record to hold parsed data
    private record ParsedPost(String postId, NewPostRequest request) {}
}
