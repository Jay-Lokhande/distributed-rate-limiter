package com.example.ratelimiterservice.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {
    
    private TokenBucket tokenBucket;
    private static final long CAPACITY = 10;
    private static final double REFILL_RATE = 5.0;
    
    @BeforeEach
    void setUp() {
        tokenBucket = new TokenBucket(CAPACITY, REFILL_RATE);
    }
    
    @Test
    void testAllowsRequestsUpToCapacity() {
        for (int i = 0; i < CAPACITY; i++) {
            assertTrue(tokenBucket.allowRequest(), "Request " + i + " should be allowed");
        }
    }
    
    @Test
    void testRejectsWhenTokensExhausted() {
        for (int i = 0; i < CAPACITY; i++) {
            tokenBucket.allowRequest();
        }
        
        assertFalse(tokenBucket.allowRequest(), "Request should be rejected when tokens exhausted");
    }
    
    @Test
    void testRefillsTokensOverTime() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) {
            tokenBucket.allowRequest();
        }
        
        assertFalse(tokenBucket.allowRequest(), "Should be rejected immediately after exhausting tokens");
        
        Thread.sleep(250);
        
        assertTrue(tokenBucket.allowRequest(), "Should allow request after token refill");
    }
    
    @Test
    void testRefillRateCalculation() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) {
            tokenBucket.allowRequest();
        }
        
        Thread.sleep(1000);
        
        long allowedCount = 0;
        for (int i = 0; i < (long) REFILL_RATE + 1; i++) {
            if (tokenBucket.allowRequest()) {
                allowedCount++;
            }
        }
        
        assertTrue(allowedCount >= REFILL_RATE - 1, 
            "Should allow approximately " + REFILL_RATE + " requests after 1 second");
    }
    
    @Test
    void testTokensNeverExceedCapacity() throws InterruptedException {
        for (int i = 0; i < CAPACITY; i++) {
            tokenBucket.allowRequest();
        }
        
        Thread.sleep(3000);
        
        long allowedCount = 0;
        for (int i = 0; i < CAPACITY * 2; i++) {
            if (tokenBucket.allowRequest()) {
                allowedCount++;
            }
        }
        
        assertTrue(allowedCount <= CAPACITY, 
            "Should not allow more than capacity even after long wait");
    }
}

