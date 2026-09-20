package com.example.highrps.post.query;

import com.example.highrps.post.domain.PostRedis;
import com.example.highrps.post.domain.PostRedisRepository;
import com.example.highrps.post.domain.PostRepository;
import com.example.highrps.post.mapper.PostEntityToPostProjectionMapper;
import com.example.highrps.shared.ResourceNotFoundException;
import com.example.highrps.shared.redis.DeletionMarkerHandler;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Query service for Post aggregate.
 * Handles all read operations with multi-layer caching strategy.
 */
@Service
@Transactional(readOnly = true)
public class PostQueryService {

    private static final Logger log = LoggerFactory.getLogger(PostQueryService.class);

    private final Cache<String, String> localCache;
    private final PostRedisRepository postRedisRepository;
    private final DeletionMarkerHandler deletionMarkerHandler;
    private final PostEntityToPostProjectionMapper postEntityToPostProjectionMapper;
    private final JsonMapper jsonMapper;
    private final PostRepository postRepository;

    /**
     * Creates a post query service backed by local, Redis, stream, and database views.
     *
     * @param localCache local post cache
     * @param postRedisRepository Redis post repository
     * @param postRepository database post repository
     * @param jsonMapper serializer for cached values
     * @param deletionMarkerHandler handler for deleted aggregates
     * @param postEntityToPostProjectionMapper mapper for converting PostEntity to PostProjection
     */
    public PostQueryService(
            Cache<String, String> localCache,
            PostRedisRepository postRedisRepository,
            PostRepository postRepository,
            JsonMapper jsonMapper,
            DeletionMarkerHandler deletionMarkerHandler,
            PostEntityToPostProjectionMapper postEntityToPostProjectionMapper) {
        this.localCache = localCache;
        this.postRedisRepository = postRedisRepository;
        this.postRepository = postRepository;
        this.postEntityToPostProjectionMapper = postEntityToPostProjectionMapper;
        this.jsonMapper = jsonMapper;
        this.deletionMarkerHandler = deletionMarkerHandler;
    }

    /**
     * Resolves a post from the read caches, stream state, or database and warms faster cache layers when possible.
     *
     * @param query the post ID query
     * @return the matching post serialized as JSON
     * @throws ResourceNotFoundException if the post is marked as deleted or cannot be found
     */
    public String getPost(PostQuery query) {
        Long postId = query.postId();
        log.debug("Querying post with id: {}", postId);

        // 1. Check tombstone (deleted posts)
        if (deletionMarkerHandler.isDeleted(DeletionMarkerHandler.POST, String.valueOf(postId))) {
            throw new ResourceNotFoundException("Post not found for id: " + postId);
        }

        // 2. Local cache (fastest)
        String cacheKey = String.valueOf(postId);
        String cached = localCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("Hit local cache for postId: {}", postId);
            return cached;
        }

        // 3. Redis materialized view (fast)
        Optional<PostRedis> redisPost = postRedisRepository.findById(postId);
        if (redisPost.isPresent()) {
            log.debug("Hit Redis for postId: {}", postId);
            PostProjection projection = fromRedis(redisPost.get());
            // Warm local cache and return JSON
            try {
                String jsonStr = jsonMapper.writeValueAsString(projection);
                localCache.put(cacheKey, jsonStr);
                return jsonStr;

            } catch (Exception e) {
                log.warn("Failed to serialize to JSON", e);
                throw new RuntimeException("Serialization error", e);
            }
        }

        // 5. Database fallback
        return postRepository
                .findByPostRefId(postId)
                .map(entity -> {
                    log.debug("Hit DB for postId: {}", postId);
                    PostProjection projection = postEntityToPostProjectionMapper.fromEntity(entity);
                    try {
                        String jsonStr = jsonMapper.writeValueAsString(projection);
                        localCache.put(cacheKey, jsonStr);
                        return jsonStr;

                    } catch (Exception e) {
                        throw new RuntimeException("Serialization error", e);
                    }
                })
                .orElseThrow(() -> new ResourceNotFoundException("Post not found for id: " + postId));
    }

    /**
     * Checks whether a post can be resolved by identifier.
     *
     * @param postId the post identifier
     * @return {@code true} when the post exists
     */
    public boolean exists(Long postId) {
        try {
            getPost(new PostQuery(postId));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    /**
     * Maps a Redis post to its read projection.
     *
     * @param postRedis the cached post
     * @return the post projection
     */
    private PostProjection fromRedis(PostRedis postRedis) {
        return new PostProjection(
                postRedis.getId(),
                postRedis.getTitle(),
                postRedis.getContent(),
                postRedis.getAuthorEmail(),
                postRedis.isPublished(),
                postRedis.getPublishedAt(),
                postRedis.getCreatedAt(),
                postRedis.getModifiedAt(),
                postRedis.getDetails(),
                postRedis.getTags() != null ? postRedis.getTags() : List.of());
    }
}
