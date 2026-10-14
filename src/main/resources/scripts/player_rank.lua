-- "Where am I and who is around me?" in one round trip: the player's score, competition rank,
-- board size, and the window of neighbours with their names.
--
-- KEYS[1] leaderboard sorted set   KEYS[2] leaderboard meta key   KEYS[3] player:names hash
-- ARGV[1] player id                ARGV[2] neighbours on each side
--
-- Returns {state} if the board is not ready, {"ready", 0} if the player has no score, otherwise
-- {"ready", 1, score, rank, total, window_start, first_rank, {id, score, ...}, {name, ...}}.
local state = redis.call('GET', KEYS[2])
if state ~= 'ready' then
  return {state or 'missing'}
end

local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not score then
  return {state, 0}
end

-- Rank = 1 + number of players with a strictly better score (ties share a rank).
local rank = redis.call('ZCOUNT', KEYS[1], '(' .. score, '+inf') + 1
local position = redis.call('ZREVRANK', KEYS[1], ARGV[1])
local total = redis.call('ZCARD', KEYS[1])

local window = tonumber(ARGV[2])
local start = math.max(0, position - window)
local range = redis.call('ZREVRANGE', KEYS[1], start, position + window, 'WITHSCORES')

local first_rank = rank
if range[1] ~= ARGV[1] then
  first_rank = redis.call('ZCOUNT', KEYS[1], '(' .. range[2], '+inf') + 1
end

local ids = {}
for i = 1, #range, 2 do
  ids[#ids + 1] = range[i]
end
local names = redis.call('HMGET', KEYS[3], unpack(ids))

return {state, 1, score, rank, total, start, first_rank, range, names}
