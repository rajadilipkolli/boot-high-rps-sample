package com.example.highrps.infrastructure.cache;

import com.example.highrps.shared.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.ReadFrom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableRedisRepositories(basePackages = "com.example.highrps")
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * Creates the shared in-process cache with the configured entry limit and a five-minute write expiry.
     *
     * @param appProperties application configuration containing the local cache limit
     * @return the cache used by application read and write paths
     */
    @Bean
    Cache localCache(AppProperties appProperties) {
        return Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getLocalMaxSize())
                // 30 s TTL: short enough that cross-node stale reads resolve quickly
                // (write on node-1 invalidates that node's entry; node-2 falls through
                // to Redis within 30 s at most), while still absorbing hot repeated reads.
                .expireAfterWrite(Duration.ofSeconds(30))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .recordStats()
                .removalListener((key, value, cause) -> {
                    // Log or emit metrics for evictions
                    log.debug("Removed key: {}, cause: {}", key, cause);
                })
                .build();
    }

    /**
     * Creates the string Redis template used for cache reservations and deletion markers.
     *
     * @param factory the Redis connection factory
     * @return a template configured with string key and value serializers
     */
    @Bean
    @Primary
    RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        var serializer = new StringRedisSerializer();
        template.setConnectionFactory(factory);
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }

    @Bean
    @Profile("production")
    LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        return clientConfigurationBuilder -> clientConfigurationBuilder.readFrom(ReadFrom.REPLICA_PREFERRED);
    }

    /**
     * Creates Redis reading conversions for byte-array, integer, and long boolean representations.
     *
     * @return the custom conversions used when reading Redis entities
     */
    @Bean
    RedisCustomConversions redisCustomConversions() {
        return new RedisCustomConversions(List.of(
                BytesToBooleanConverter.INSTANCE, IntegerToBooleanConverter.INSTANCE, LongToBooleanConverter.INSTANCE));
    }

    @ReadingConverter
    enum BytesToBooleanConverter implements Converter<byte[], Boolean> {
        INSTANCE;

        /**
         * Interprets UTF-8 bytes as true only for {@code "1"} or case-insensitive {@code "true"}.
         *
         * @param source the encoded flag; whitespace is not trimmed
         * @return false for null, empty, or any other input
         */
        @Override
        public Boolean convert(byte[] source) {
            if (source.length == 0) {
                return Boolean.FALSE;
            }
            String s = new String(source, StandardCharsets.UTF_8);
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
    }

    @ReadingConverter
    enum IntegerToBooleanConverter implements Converter<Integer, Boolean> {
        INSTANCE;

        /**
         * Interprets any nonzero value, including a negative value, as true.
         *
         * @param source the numeric flag
         * @return false for null or zero, otherwise true
         */
        @Override
        public Boolean convert(Integer source) {
            return source != 0;
        }
    }

    @ReadingConverter
    enum LongToBooleanConverter implements Converter<Long, Boolean> {
        INSTANCE;

        /**
         * Interprets any nonzero value, including a negative value, as true.
         *
         * @param source the numeric flag
         * @return false for null or zero, otherwise true
         */
        @Override
        public Boolean convert(Long source) {
            return source != 0L;
        }
    }
}
