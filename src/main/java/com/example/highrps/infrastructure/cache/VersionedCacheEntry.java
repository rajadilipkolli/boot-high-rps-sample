package com.example.highrps.infrastructure.cache;

public record VersionedCacheEntry(String value, long version) {}
