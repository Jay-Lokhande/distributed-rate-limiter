-- Token Bucket Rate Limiting Lua Script for Redis
-- Inputs: key, capacity, refillRatePerSecond, currentTimestamp
-- Returns: 1 if request allowed, 0 if rate limited

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRatePerSecond = tonumber(ARGV[2])
local currentTimestamp = tonumber(ARGV[3])

-- Get current state from Redis hash (tokens and last_refill_time)
local hashData = redis.call('HGETALL', key)
local currentTokens = 0
local lastRefillTime = currentTimestamp

-- Parse hash data if it exists
if #hashData > 0 then
    for i = 1, #hashData, 2 do
        if hashData[i] == 'tokens' then
            currentTokens = tonumber(hashData[i + 1])
        elseif hashData[i] == 'last_refill_time' then
            lastRefillTime = tonumber(hashData[i + 1])
        end
    end
end

-- Calculate elapsed time since last refill (in milliseconds)
local elapsedTime = currentTimestamp - lastRefillTime

-- Refill tokens based on elapsed time if time has passed
if elapsedTime > 0 then
    -- Calculate tokens to add: (elapsedTime in seconds) * refillRatePerSecond
    local tokensToAdd = (elapsedTime / 1000.0) * refillRatePerSecond
    -- Add tokens but cap at capacity
    currentTokens = math.min(capacity, currentTokens + tokensToAdd)
end

-- Check if we have at least one token available
if currentTokens < 1 then
    -- No tokens available, update hash with current state and return 0 (rate limited)
    redis.call('HSET', key, 'tokens', currentTokens, 'last_refill_time', currentTimestamp)
    -- Set TTL on key (e.g., 1 hour = 3600 seconds)
    redis.call('EXPIRE', key, 3600)
    return 0
end

-- We have tokens available, decrement by 1
currentTokens = currentTokens - 1

-- Update hash with new token count and timestamp
redis.call('HSET', key, 'tokens', currentTokens, 'last_refill_time', currentTimestamp)
-- Set TTL on key (e.g., 1 hour = 3600 seconds)
redis.call('EXPIRE', key, 3600)

-- Return 1 to indicate request is allowed
return 1

