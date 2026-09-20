package com.example.highrps.infrastructure.redis;

import com.example.highrps.infrastructure.cache.VersionedCacheEntry;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisCacheInvalidationListener implements MessageListener, ApplicationListener<ContextRefreshedEvent> {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheInvalidationListener.class);

    private final Cache<String, VersionedCacheEntry> localCache;
    private final JsonMapper objectMapper;

    public RedisCacheInvalidationListener(Cache<String, VersionedCacheEntry> localCache, JsonMapper objectMapper) {
        this.localCache = localCache;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody());
        RedisCacheInvalidationPublisher.CacheInvalidationMessage msg =
                objectMapper.readValue(payload, RedisCacheInvalidationPublisher.CacheInvalidationMessage.class);

        String cacheKey = msg.key();
        VersionedCacheEntry current = localCache.getIfPresent(cacheKey);

        if (current != null) {
            if (msg.version() >= current.version()) {
                log.debug(
                        "Evicting stale cache entry {} (incoming version {}, local version {})",
                        cacheKey,
                        msg.version(),
                        current.version());
                localCache.invalidate(cacheKey);
            } else {
                log.debug(
                        "Ignoring outdated invalidation for {} (incoming version {}, local version {})",
                        cacheKey,
                        msg.version(),
                        current.version());
            }
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Application started/refreshed, clearing local cache");
        localCache.invalidateAll();
    }
}
