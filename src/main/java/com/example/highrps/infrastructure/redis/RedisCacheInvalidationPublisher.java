package com.example.highrps.infrastructure.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RedisCacheInvalidationPublisher {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheInvalidationPublisher.class);

    public static final String INVALIDATION_TOPIC = "cache-invalidation";

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;

    public RedisCacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate, JsonMapper jsonMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jsonMapper = jsonMapper;
    }

    public void publishInvalidation(String aggregateType, String key, long version) {
        CacheInvalidationMessage message = new CacheInvalidationMessage(aggregateType, key, version);
        String payload = jsonMapper.writeValueAsString(message);
        log.debug("Publishing cache invalidation: {}", payload);
        stringRedisTemplate.convertAndSend(INVALIDATION_TOPIC, payload);
    }

    public record CacheInvalidationMessage(String aggregateType, String key, long version) {}
}
