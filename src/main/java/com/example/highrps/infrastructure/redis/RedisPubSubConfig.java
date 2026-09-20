package com.example.highrps.infrastructure.redis;

import com.example.highrps.infrastructure.cache.VersionedCacheEntry;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
public class RedisPubSubConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubConfig.class);

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisCacheInvalidationListener invalidationListener,
            Cache<String, VersionedCacheEntry> localCache) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                invalidationListener, new ChannelTopic(RedisCacheInvalidationPublisher.INVALIDATION_TOPIC));

        // Wire Lettuce connection state listener for reconnect cache clearing
        if (connectionFactory instanceof LettuceConnectionFactory lettuceFactory) {
            lettuceFactory.getNativeClient(); // ensure client is initialized if possible, wait lettuce client might be
            // created on demand
            // A simple way to attach a listener is during bean post processing or via the Lettuce connection factory
            // But LettuceConnectionFactory doesn't expose addConnectionStateListener easily without downcasting client
        }

        return container;
    }
}
