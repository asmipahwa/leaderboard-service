-- Records a player's best score in a leaderboard sorted set and returns rank information,
-- all in one atomic round trip.
--
-- KEYS[1] leaderboard sorted set     KEYS[2] leaderboard meta key     KEYS[3] board version counter
-- ARGV[1] player id                   ARGV[2] ranking score (already negated for ASC boards)
-- ARGV[3] expire-at epoch seconds, or 0 for boards that never expire
--
-- Returns {cached, previous_rank, new_rank}. cached = 0 means the sorted set has not been built
-- yet; the caller then rebuilds it from Postgres. Writing into a half-built set would hand out
-- wrong ranks, so we don't.
if redis.call('EXISTS', KEYS[2]) == 0 then
  return {0, 0, 0}
end

-- Rank = 1 + number of members with a strictly higher score (standard competition ranking).
local previous_rank = 0
local old = redis.call('ZSCORE', KEYS[1], ARGV[1])
if old then
  previous_rank = redis.call('ZCOUNT', KEYS[1], '(' .. old, '+inf') + 1
end

-- GT only ever raises a member's score. Applying the same update twice, or two updates in either
-- order, ends in the same state, so retries and races are harmless.
-- CH makes ZADD return how many members actually changed, so the version only moves on a real
-- improvement (it backs GetTopNResponse.entries_version).
local changed = redis.call('ZADD', KEYS[1], 'GT', 'CH', ARGV[2], ARGV[1])
if changed > 0 then
  redis.call('INCR', KEYS[3])
end

local expire_at = tonumber(ARGV[3])
if expire_at > 0 then
  redis.call('EXPIREAT', KEYS[1], expire_at)
  redis.call('EXPIREAT', KEYS[3], expire_at)
end

local best = redis.call('ZSCORE', KEYS[1], ARGV[1])
local new_rank = redis.call('ZCOUNT', KEYS[1], '(' .. best, '+inf') + 1
return {1, previous_rank, new_rank}
