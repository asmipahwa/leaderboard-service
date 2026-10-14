-- Reads one leaderboard page in a single round trip: board state, size, the entries with their
-- scores, their display names, and the rank of the first entry.
--
-- KEYS[1] leaderboard sorted set   KEYS[2] leaderboard meta key   KEYS[3] player:names hash
-- KEYS[4] board version counter
-- ARGV[1] start position (0-based) ARGV[2] stop position (inclusive)
--
-- Returns {state} if the board is not ready, otherwise
-- {"ready", total, first_rank, {id1, score1, id2, score2, ...}, {name1, name2, ...}, version}.
--
-- Note: in Redis Cluster these keys would need a shared hash tag to live on the same node.
local state = redis.call('GET', KEYS[2])
if state ~= 'ready' then
  return {state or 'missing'}
end

local total = redis.call('ZCARD', KEYS[1])
local range = redis.call('ZREVRANGE', KEYS[1], ARGV[1], ARGV[2], 'WITHSCORES')

local ids = {}
for i = 1, #range, 2 do
  ids[#ids + 1] = range[i]
end
local names = {}
if #ids > 0 then
  names = redis.call('HMGET', KEYS[3], unpack(ids))
end

-- A page that starts in the middle of a tie must show the tied rank, so count strictly better.
local first_rank = 1
if tonumber(ARGV[1]) > 0 and #range > 0 then
  first_rank = redis.call('ZCOUNT', KEYS[1], '(' .. range[2], '+inf') + 1
end

local version = tonumber(redis.call('GET', KEYS[4]) or '0')
return {state, total, first_rank, range, names, version}
