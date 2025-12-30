package com.example.ratelimiterservice.ratelimiter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RedisRateLimiter {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private DefaultRedisScript<Long> script;
    
    private final ConcurrentHashMap<String, TokenBucket> inMemoryBuckets = new ConcurrentHashMap<>();
    
    public RedisRateLimiter(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @PostConstruct
    public void init() throws IOException {
        // Load Lua script from classpath resources
        ClassPathResource scriptResource = new ClassPathResource("scripts/token_bucket.lua");
        String scriptContent = new String(scriptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        
        script = new DefaultRedisScript<>();
        script.setScriptText(scriptContent);
        script.setResultType(Long.class);
    }
    
    public boolean allowRequest(String key, long capacity, double refillRatePerSecond) {
        try {
            // Execute Lua script atomically in Redis with key and parameters
            long currentTimestamp = System.currentTimeMillis();
            
            Long result = redisTemplate.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillRatePerSecond),
                String.valueOf(currentTimestamp)
            );
            // Lua script returns 1 for allow, 0 for deny
            return result != null && result == 1;
        } catch (Exception e) {
            // Redis connection or runtime exception occurred
            // Fallback to in-memory TokenBucket per user
            // Tradeoff: In-memory fallback means rate limits are per-application-instance,
            // not shared across instances. Each instance maintains its own token buckets,
            // which can lead to higher total throughput than intended when multiple instances are running.
            // However, this ensures the application remains available during Redis downtime.
            String userId = extractUserIdFromKey(key);
            TokenBucket bucket = inMemoryBuckets.computeIfAbsent(
                userId,
                k -> new TokenBucket(capacity, refillRatePerSecond)
            );
            return bucket.allowRequest();
        }
    }
    
    private String extractUserIdFromKey(String key) {
        if (key.startsWith("rate-limit:")) {
            return key.substring("rate-limit:".length());
        }
        return key;
    }
}
