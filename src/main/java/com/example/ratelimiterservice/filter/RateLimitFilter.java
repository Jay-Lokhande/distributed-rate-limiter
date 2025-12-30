package com.example.ratelimiterservice.filter;

import com.example.ratelimiterservice.ratelimiter.RedisRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {
    
    private final RedisRateLimiter redisRateLimiter;
    
    @Value("${rate-limit.capacity:100}")
    private long capacity;
    
    @Value("${rate-limit.refill-rate-per-second:10.0}")
    private double refillRatePerSecond;
    
    public RateLimitFilter(RedisRateLimiter redisRateLimiter) {
        this.redisRateLimiter = redisRateLimiter;
    }
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        // Extract user identifier from header or IP address
        String userIdentifier = extractUserIdentifier(request);
        String rateLimitKey = "rate-limit:" + userIdentifier;
        
        // Check rate limit using Redis
        boolean allowed = redisRateLimiter.allowRequest(rateLimitKey, capacity, refillRatePerSecond);
        
        // Return 429 if rate limited, otherwise proceed
        if (!allowed) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Rate limit exceeded\"}");
            return;
        }
        
        filterChain.doFilter(request, response);
    }
    
    private String extractUserIdentifier(HttpServletRequest request) {
        // Try X-User-Id header first, fallback to IP address
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }
        return getClientIpAddress(request);
    }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}

