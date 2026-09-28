-- Atomic hosted-session reservation (WP-FIX-G, G-M4-V1-01).
-- Sockbowl-game only (sockbowl-questions has no concurrent-session metric).
--
-- KEYS[1]  sessions ZSET   usage:{owner}:sessions
-- ARGV[1]  now (ms)
-- ARGV[2]  idle cutoff (ms); members with score <= this are dropped first,
--          same rule as HostedSessionQuota#countActive's own sweep
-- ARGV[3]  limit (caller never invokes this script when the limit is -1
--          (unlimited) or quotas are disabled)
-- ARGV[4]  reservation member, e.g. 'rsv:{uuid}'
-- ARGV[5]  key TTL in seconds, refreshed on every write
--
-- Returns {allowed (1/0), used}. `used` is the count BEFORE the reservation
-- is added, i.e. what the caller was compared against. On success the
-- reservation member is added with score=now, so it counts like any other
-- session (and eventually idles out on its own) until GameSessionController
-- swaps it for the real session id, or releases it on failure.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])
local used = redis.call('ZCARD', KEYS[1])
local limit = tonumber(ARGV[3])
if used >= limit then
  return {0, used}
end
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
redis.call('EXPIRE', KEYS[1], ARGV[5])
return {1, used}
