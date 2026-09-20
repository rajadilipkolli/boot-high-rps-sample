package com.example.highrps.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NestedConfigurationProperty
    private Batch batch = new Batch();

    @NestedConfigurationProperty
    private Cache cache = new Cache();

    public Batch getBatch() {
        return batch;
    }

    public void setBatch(Batch batch) {
        this.batch = batch;
    }

    /**
     * Returns the local cache settings.
     *
     * @return the cache settings
     */
    public Cache getCache() {
        return cache;
    }

    /**
     * Replaces the local cache settings.
     *
     * @param cache the cache settings to use
     */
    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public static class Cache {
        private long localMaxSize = 10000;

        /**
         * Returns the maximum number of entries retained by the local cache.
         *
         * @return the local cache entry limit
         */
        public long getLocalMaxSize() {
            return localMaxSize;
        }

        /**
         * Sets the maximum number of entries retained by the local cache.
         *
         * @param localMaxSize the local cache entry limit
         */
        public void setLocalMaxSize(long localMaxSize) {
            this.localMaxSize = localMaxSize;
        }
    }

    public static class Batch {
        private String queueKey = "events:queue";
        private int size = 5000;
        private long delayMs = 500;

        public String getQueueKey() {
            return queueKey;
        }

        public void setQueueKey(String queueKey) {
            this.queueKey = queueKey;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }
    }
}
