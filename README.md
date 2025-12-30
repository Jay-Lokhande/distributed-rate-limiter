# Rate Limiter Service

A production-ready rate limiting service implementing the Token Bucket algorithm with Redis-backed distributed rate limiting and in-memory fallback.

## Problem Statement

Rate limiting is essential for:
- **API Protection**: Prevent abuse and ensure fair resource usage
- **Cost Control**: Limit expensive operations (database queries, external API calls)
- **Stability**: Protect backend systems from traffic spikes
- **Compliance**: Enforce usage quotas per user or API key

This service provides a distributed, scalable rate limiting solution that maintains availability even during Redis outages.

## High-Level Architecture

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ HTTP Request
       ▼
┌─────────────────────┐
│  RateLimitFilter    │  ← OncePerRequestFilter (Spring)
│  - Extract User ID  │
│  - Build Redis Key  │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│  RedisRateLimiter    │
│  - Execute Lua Script│
│  - Fallback Logic   │
└──────┬──────────────┘
       │
       ├──► Redis (Primary)
       │    └── Lua Script (Atomic)
       │
       └──► In-Memory (Fallback)
            └── TokenBucket per User
```

### Components

1. **RateLimitFilter**: Servlet filter intercepting all HTTP requests
2. **RedisRateLimiter**: Core rate limiting logic with Redis + fallback
3. **TokenBucket**: In-memory token bucket implementation
4. **Lua Script**: Atomic Redis operation for distributed rate limiting

## Token Bucket Algorithm

### Overview

Token Bucket is a rate limiting algorithm that allows bursts up to a maximum capacity while maintaining a steady refill rate.

### How It Works

1. **Bucket Capacity**: Maximum number of tokens (e.g., 100)
2. **Refill Rate**: Tokens added per second (e.g., 10 tokens/sec)
3. **Request Processing**:
   - Each request consumes 1 token
   - If tokens available → allow request, decrement token
   - If no tokens → reject request (HTTP 429)

### Token Refill Logic

```
elapsedTime = currentTime - lastRefillTime
tokensToAdd = (elapsedTime / 1000) * refillRatePerSecond
currentTokens = min(capacity, currentTokens + tokensToAdd)
```

**Example**:
- Capacity: 100 tokens
- Refill Rate: 10 tokens/second
- After 1 second of no requests: +10 tokens (capped at 100)
- After 2 seconds: +20 tokens (capped at 100)

### Advantages

- **Burst Handling**: Allows short bursts up to capacity
- **Smooth Rate**: Maintains average rate over time
- **Predictable**: Easy to reason about and configure

## Redis + Lua Atomicity

### Why Lua Scripts?

Rate limiting requires **atomic operations**:
1. Read current token count
2. Calculate refill
3. Check availability
4. Decrement token
5. Update timestamp

Without atomicity, concurrent requests could:
- Read the same token count
- Both be allowed when only one should be
- Exceed the rate limit

### Implementation

The Lua script (`token_bucket.lua`) executes atomically in Redis:

```lua
-- All operations happen atomically
local hashData = redis.call('HGETALL', key)
-- Calculate refill
-- Check tokens
-- Decrement if allowed
-- Update hash
return 1 or 0
```

### Benefits

- **Atomicity**: Single Redis command ensures consistency
- **Performance**: Single round-trip to Redis
- **Distributed**: Works across multiple application instances
- **Reliability**: Redis handles script execution atomically

## Failure Handling Strategy

### Graceful Degradation

When Redis is unavailable, the service falls back to in-memory `TokenBucket` instances:

```java
try {
    return redisTemplate.execute(script, ...);
} catch (Exception e) {
    // Fallback to in-memory TokenBucket
    TokenBucket bucket = inMemoryBuckets.computeIfAbsent(
        userId, k -> new TokenBucket(capacity, refillRate)
    );
    return bucket.allowRequest();
}
```

### Tradeoffs

**Advantages**:
- ✅ Application remains available during Redis outages
- ✅ No request failures due to Redis downtime
- ✅ Automatic failover without configuration

**Limitations**:
- ⚠️ Rate limits are **per-instance**, not shared across instances
- ⚠️ With N instances, effective rate limit = N × configured limit
- ⚠️ In-memory buckets lost on application restart

### Production Considerations

For production deployments:
- Monitor Redis health and alert on fallback usage
- Consider circuit breaker pattern for Redis failures
- Use Redis Sentinel/Cluster for high availability
- Implement distributed coordination (e.g., ZooKeeper) if strict limits required

## Testing Strategy

### Test Coverage

1. **Unit Tests** (`TokenBucketTest`):
   - Capacity enforcement
   - Token exhaustion
   - Refill rate accuracy
   - Thread safety

2. **Integration Tests** (`RedisRateLimiterTest`):
   - Redis success scenarios
   - Redis failure fallback
   - Per-user bucket isolation
   - Key extraction logic

3. **Concurrency Tests** (`ConcurrencyTest`):
   - 50-100 parallel requests
   - Capacity enforcement under load
   - Multiple users concurrently
   - Refill during concurrent access

### Running Tests

```bash
mvn test
```

### Test Metrics

- **Concurrency**: 100 parallel threads
- **Capacity**: 50 tokens
- **Verification**: Exact capacity enforcement, no race conditions

## Tradeoffs & Limitations

### Current Limitations

1. **Per-Instance Fallback**: In-memory buckets not shared across instances
2. **No Persistence**: In-memory state lost on restart
3. **Fixed Configuration**: Rate limits configured globally, not per-endpoint
4. **No Metrics**: No built-in monitoring/metrics export

### Design Decisions

| Decision | Rationale | Alternative |
|----------|-----------|-------------|
| Token Bucket vs Leaky Bucket | Allows bursts, more intuitive | Leaky Bucket (smoother but no bursts) |
| Redis Lua vs Multiple Commands | Atomicity, performance | Multiple Redis commands (race conditions) |
| In-Memory Fallback vs Fail-Open | Availability over strict limits | Fail-closed (strict but unavailable) |
| Filter vs Interceptor | Earlier in request lifecycle | HandlerInterceptor (later, less control) |

### Future Enhancements

- Per-endpoint rate limits
- Sliding window algorithm option
- Metrics export (Prometheus/Micrometer)
- Distributed coordination for strict limits
- Rate limit headers in responses (X-RateLimit-*)

## How to Run Locally

### Prerequisites

- Java 17+
- Maven 3.6+
- Redis 6.0+ (optional, falls back to in-memory)

### Setup

1. **Clone and Build**:
```bash
cd rate-limiter-service
mvn clean install
```

2. **Start Redis** (optional):
```bash
docker run -d -p 6379:6379 redis:7-alpine
```

3. **Configure** (optional):
Edit `src/main/resources/application.yml`:
```yaml
rate-limit:
  capacity: 100
  refill-rate-per-second: 10.0
```

4. **Run Application**:
```bash
mvn spring-boot:run
```

The service starts on `http://localhost:8080`

### Environment Variables

```bash
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=  # Optional
```

## Sample API Usage

### Successful Request

```bash
curl -H "X-User-Id: user123" http://localhost:8080/api/endpoint
```

**Response**: `200 OK`

### Rate Limited Request

After exceeding the limit:

```bash
curl -H "X-User-Id: user123" http://localhost:8080/api/endpoint
```

**Response**: `429 Too Many Requests`
```json
{
  "message": "Rate limit exceeded"
}
```

### User Identification

The service identifies users by:
1. `X-User-Id` header (preferred)
2. `X-Forwarded-For` header (if behind proxy)
3. `X-Real-IP` header (if behind proxy)
4. Remote IP address (fallback)

### Testing Rate Limits

```bash
# Send 101 requests (assuming capacity=100)
for i in {1..101}; do
  curl -H "X-User-Id: test-user" http://localhost:8080/api/endpoint
done
```

Expected: First 100 succeed, 101st returns 429.

## ## Design Decisions & Tradeoffs

### Q1: Why Token Bucket over Sliding Window?

**A**: Token Bucket allows **bursts** up to capacity while maintaining average rate. Sliding Window is smoother but doesn't handle bursts well. For APIs with variable traffic, Token Bucket provides better user experience while still enforcing limits.

### Q2: How do you ensure atomicity in distributed rate limiting?

**A**: We use **Redis Lua scripts** that execute atomically on the Redis server. The entire operation (read, calculate, update) happens in a single atomic command, preventing race conditions across multiple application instances.

### Q3: What happens when Redis is down?

**A**: The service **gracefully degrades** to in-memory TokenBucket instances per user. This ensures availability but with a tradeoff: rate limits become per-instance rather than shared. Each instance maintains its own buckets, so with N instances, the effective limit is N × configured limit.

### Q4: How would you handle rate limiting across multiple data centers?

**A**: Options:
1. **Centralized Redis Cluster**: Single Redis cluster shared across regions (latency tradeoff)
2. **Regional Redis + Coordination**: Each region has Redis, coordinate via message queue
3. **Distributed Consensus**: Use ZooKeeper/etcd for strict global limits
4. **Per-Region Limits**: Accept regional limits, sum for global (simpler)

### Q5: How do you test concurrency in rate limiting?

**A**: Use `ExecutorService` with 50-100 threads, `CountDownLatch` for synchronization, and `AtomicInteger` for counting. Verify that exactly `capacity` requests are allowed, regardless of thread scheduling.

### Q6: What's the time complexity of the Token Bucket algorithm?

**A**: **O(1)** for both operations:
- `allowRequest()`: Constant time (simple arithmetic, hash lookup)
- Token refill: O(1) (single calculation)

Storage: O(U) where U = number of unique users.

### Q7: How would you implement per-endpoint rate limits?

**A**: 
1. Extract endpoint from request path
2. Build composite key: `rate-limit:{userId}:{endpoint}`
3. Configure limits per endpoint in config
4. Use same TokenBucket logic with endpoint-specific capacity/rate

### Q8: What metrics would you expose for monitoring?

**A**:
- `rate_limit_allowed_total{user_id}`: Counter of allowed requests
- `rate_limit_denied_total{user_id}`: Counter of denied requests
- `rate_limit_fallback_active`: Gauge (1 if using fallback, 0 if Redis)
- `rate_limit_redis_latency`: Histogram of Redis operation time

### Q9: How do you prevent memory leaks in the in-memory fallback?

**A**: Options:
1. **TTL-based cleanup**: Remove buckets unused for X minutes
2. **LRU cache**: Use `Caffeine` or `Guava Cache` with size/expiry limits
3. **Periodic cleanup**: Background thread removes stale entries
4. **Bounded map**: Limit total number of buckets

Current implementation: No cleanup (acceptable for short Redis outages).

### Q10: Why use a filter instead of an interceptor?

**A**: **Filter** runs earlier in the request lifecycle, before Spring MVC processing. This:
- Reduces overhead (reject before controller execution)
- Works for all endpoints automatically
- Can't be bypassed by controllers
- Better performance for high-volume rate limiting

---

## License

This project is provided as-is for educational and interview purposes.

