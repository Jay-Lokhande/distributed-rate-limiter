package com.example.ratelimiterservice.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    private RedisRateLimiter redisRateLimiter;
    private static final long CAPACITY = 10;
    private static final double REFILL_RATE = 5.0;
    private static final String TEST_KEY = "rate-limit:user123";
    
    @BeforeEach
    void setUp() throws Exception {
        redisRateLimiter = new RedisRateLimiter(redisTemplate);
        redisRateLimiter.init();
    }
    
    @Test
    void testAllowWhenUnderLimit() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenReturn(1L);
        
        boolean result = redisRateLimiter.allowRequest(TEST_KEY, CAPACITY, REFILL_RATE);
        
        assertTrue(result, "Should allow request when under limit");
        verify(redisTemplate, times(1)).execute(any(), anyList(), anyString(), anyString(), anyString());
    }
    
    @Test
    void testDenyWhenLimitExceeded() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenReturn(0L);
        
        boolean result = redisRateLimiter.allowRequest(TEST_KEY, CAPACITY, REFILL_RATE);
        
        assertFalse(result, "Should deny request when limit exceeded");
        verify(redisTemplate, times(1)).execute(any(), anyList(), anyString(), anyString(), anyString());
    }
    
    @Test
    void testFallbackToInMemoryOnRedisException() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        boolean result = redisRateLimiter.allowRequest(TEST_KEY, CAPACITY, REFILL_RATE);
        
        assertTrue(result, "Should fallback to in-memory bucket and allow request");
        verify(redisTemplate, times(1)).execute(any(), anyList(), anyString(), anyString(), anyString());
    }
    
    @Test
    void testFallbackInMemoryRespectsCapacity() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(redisRateLimiter.allowRequest(TEST_KEY, CAPACITY, REFILL_RATE),
                "Should allow request " + i + " via fallback");
        }
        
        assertFalse(redisRateLimiter.allowRequest(TEST_KEY, CAPACITY, REFILL_RATE),
            "Should deny request when in-memory bucket exhausted");
    }
    
    @Test
    void testFallbackCreatesSeparateBucketsPerUser() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        String key1 = "rate-limit:user1";
        String key2 = "rate-limit:user2";
        
        for (int i = 0; i < CAPACITY; i++) {
            redisRateLimiter.allowRequest(key1, CAPACITY, REFILL_RATE);
        }
        
        assertFalse(redisRateLimiter.allowRequest(key1, CAPACITY, REFILL_RATE),
            "User1 should be rate limited");
        assertTrue(redisRateLimiter.allowRequest(key2, CAPACITY, REFILL_RATE),
            "User2 should still have tokens");
    }
    
    @Test
    void testExtractUserIdFromKey() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        String keyWithPrefix = "rate-limit:user123";
        String keyWithoutPrefix = "user456";
        
        redisRateLimiter.allowRequest(keyWithPrefix, CAPACITY, REFILL_RATE);
        redisRateLimiter.allowRequest(keyWithoutPrefix, CAPACITY, REFILL_RATE);
        
        for (int i = 0; i < CAPACITY; i++) {
            redisRateLimiter.allowRequest(keyWithPrefix, CAPACITY, REFILL_RATE);
        }
        
        assertFalse(redisRateLimiter.allowRequest(keyWithPrefix, CAPACITY, REFILL_RATE),
            "User from prefixed key should be rate limited");
        assertTrue(redisRateLimiter.allowRequest(keyWithoutPrefix, CAPACITY, REFILL_RATE),
            "User from non-prefixed key should still have tokens");
    }
}

