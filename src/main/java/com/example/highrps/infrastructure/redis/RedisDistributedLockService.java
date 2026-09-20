package com.example.highrps.infrastructure.redis;

import com.example.highrps.shared.DistributedLockService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);

    private final RedisScriptExecutor redisScriptExecutor;

    public RedisDistributedLockService(RedisScriptExecutor redisScriptExecutor) {
        this.redisScriptExecutor = redisScriptExecutor;
    }

    @Override
    public boolean acquireLock(String key, String owner, long ttlSeconds) {
        String lockKey = "lock:" + key;
        Boolean acquired = redisScriptExecutor.setIfNotExists(lockKey, owner, ttlSeconds);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releaseLock(String key, String owner) {
        String lockKey = "lock:" + key;
        String script = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """;
        redisScriptExecutor.executeScript(script, Long.class, List.of(lockKey), owner);
    }
}
