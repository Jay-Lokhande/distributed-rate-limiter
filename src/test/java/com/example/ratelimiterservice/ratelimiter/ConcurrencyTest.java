package com.example.ratelimiterservice.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcurrencyTest {
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    private RedisRateLimiter redisRateLimiter;
    private TokenBucket tokenBucket;
    private static final long CAPACITY = 50;
    private static final double REFILL_RATE = 10.0;
    private static final int THREAD_COUNT = 100;
    
    @BeforeEach
    void setUp() throws Exception {
        redisRateLimiter = new RedisRateLimiter(redisTemplate);
        redisRateLimiter.init();
        tokenBucket = new TokenBucket(CAPACITY, REFILL_RATE);
    }
    
    @Test
    void testTokenBucketConcurrency() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    if (tokenBucket.allowRequest()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        assertEquals(THREAD_COUNT, allowedCount.get() + deniedCount.get(),
            "Total requests should equal thread count");
        assertEquals(CAPACITY, allowedCount.get(),
            "Only capacity number of requests should be allowed");
        assertEquals(THREAD_COUNT - CAPACITY, deniedCount.get(),
            "Remaining requests should be denied");
    }
    
    @Test
    void testRedisRateLimiterConcurrencyWithFallback() throws InterruptedException {
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        String testKey = "rate-limit:concurrent-user";
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    if (redisRateLimiter.allowRequest(testKey, CAPACITY, REFILL_RATE)) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        assertEquals(THREAD_COUNT, allowedCount.get() + deniedCount.get(),
            "Total requests should equal thread count");
        assertEquals(CAPACITY, allowedCount.get(),
            "Only capacity number of requests should be allowed via fallback");
        assertEquals(THREAD_COUNT - CAPACITY, deniedCount.get(),
            "Remaining requests should be denied");
    }
    
    @Test
    void testMultipleUsersConcurrency() throws InterruptedException {
        when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis connection failed"));
        
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        List<Boolean> results = new CopyOnWriteArrayList<>();
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int userId = i % 5;
            executor.submit(() -> {
                try {
                    String key = "rate-limit:user" + userId;
                    results.add(redisRateLimiter.allowRequest(key, CAPACITY, REFILL_RATE));
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        long allowedCount = results.stream().filter(b -> b).count();
        assertEquals(THREAD_COUNT, results.size(), "All requests should be processed");
        assertEquals(THREAD_COUNT, allowedCount,
            "All requests should be allowed since each user gets " + (THREAD_COUNT / 5) + 
            " requests (less than capacity " + CAPACITY + ")");
    }
    
    @Test
    void testConcurrentRefill() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(20);
        AtomicInteger allowedCount = new AtomicInteger(0);
        
        for (int i = 0; i < CAPACITY; i++) {
            tokenBucket.allowRequest();
        }
        
        Thread.sleep(1100);
        
        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    if (tokenBucket.allowRequest()) {
                        allowedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        assertTrue(allowedCount.get() >= (long) REFILL_RATE - 2,
            "Should allow approximately " + REFILL_RATE + " requests after refill");
    }
}

