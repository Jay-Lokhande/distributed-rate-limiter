package com.example.ratelimiterservice.ratelimiter;

public class TokenBucket {
    
    private final long capacity;
    private final double refillRatePerSecond;
    private long currentTokens;
    private long lastRefillTimestamp;
    
    public TokenBucket(long capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.currentTokens = capacity;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }
    
    public synchronized boolean allowRequest() {
        // Get current timestamp and calculate elapsed time since last refill
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - lastRefillTimestamp;
        
        if (elapsedTime > 0) {
            // Calculate tokens to add based on elapsed time (convert ms to seconds)
            double tokensToAdd = (elapsedTime / 1000.0) * refillRatePerSecond;
            // Refill tokens up to capacity limit, never exceed capacity
            currentTokens = Math.min(capacity, currentTokens + (long) tokensToAdd);
            lastRefillTimestamp = currentTime;
        }
        
        if (currentTokens >= 1) {
            currentTokens--;
            return true;
        }
        
        return false;
    }
    
    public synchronized long getCurrentTokens() {
        return currentTokens;
    }
}
