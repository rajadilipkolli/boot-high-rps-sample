package com.example.highrps.shared;

public interface DistributedLockService {
    boolean acquireLock(String key, String owner, long ttlSeconds);

    void releaseLock(String key, String owner);
}
