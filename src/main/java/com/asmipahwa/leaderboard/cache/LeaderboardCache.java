package com.asmipahwa.leaderboard.cache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * All Redis sorted-set operations for leaderboards, in one place.
 *
 * <p>A sorted set keeps members ordered by score, so the reads players do most (top N, my rank) are
 * O(log N) instead of sorting a table. Scores are stored as "ranking scores" (higher is always
 * better, see {@code SortOrder#toRankingScore}), so this class never needs to know the board's sort
 * order. Redis is a cache here: everything in it can be rebuilt from Postgres.
 */
@Component
public class LeaderboardCache {

  /** Whether a board's sorted set can be trusted for reads. */
  public enum State {
    MISSING,
    BUILDING,
    READY
  }

  static final String BUILDING = "building";
  static final String READY = "ready";

  /** A sorted-set member with its ranking score. */
  public record Member(String playerId, double rankingScore) {}

  /**
   * One page of a board, read in a single round trip.
   *
   * @param state whether the set is complete (other fields are empty unless READY)
   * @param total number of players on the board
   * @param firstRank competition rank of the first member
   * @param members page entries, best first
   * @param names display names by player id (players missing from the cache are absent)
   * @param version board version (see {@link BoardKey#versionKey()})
   */
  public record Page(
      State state,
      long total,
      long firstRank,
      List<Member> members,
      java.util.Map<String, String> names,
      long version) {}

  /**
   * Outcome of {@link #recordScore}.
   *
   * @param cached false if the board isn't built yet (nothing was written)
   * @param previousRank rank before the update, 0 if the player wasn't ranked
   * @param newRank rank after the update
   */
  public record RankChange(boolean cached, long previousRank, long newRank) {}

  private final StringRedisTemplate redis;

  @SuppressWarnings("rawtypes")
  private final RedisScript<List> recordScoreScript;

  @SuppressWarnings("rawtypes")
  private final RedisScript<List> topNScript;

  @SuppressWarnings("rawtypes")
  private final RedisScript<List> playerRankScript;

  @SuppressWarnings("rawtypes")
  public LeaderboardCache(
      StringRedisTemplate redis,
      RedisScript<List> recordScoreScript,
      RedisScript<List> topNScript,
      RedisScript<List> playerRankScript) {
    this.redis = redis;
    this.recordScoreScript = recordScoreScript;
    this.topNScript = topNScript;
    this.playerRankScript = playerRankScript;
  }

  /**
   * A player's position plus the neighbours around them.
   *
   * @param state whether the set is complete (other fields are empty unless READY)
   * @param ranked false if the player has no score on this board
   * @param rankingScore the player's ranking score
   * @param rank the player's competition rank
   * @param total players on the board
   * @param windowStart 0-based position of the first neighbour
   * @param firstRank competition rank of the first neighbour
   * @param members the window, best first (includes the player)
   * @param names display names by player id
   */
  public record Neighborhood(
      State state,
      boolean ranked,
      double rankingScore,
      long rank,
      long total,
      long windowStart,
      long firstRank,
      List<Member> members,
      java.util.Map<String, String> names) {}

  /** Reads a player's rank and neighbours in one {@code EVALSHA} (scripts/player_rank.lua). */
  public Neighborhood neighborhood(BoardKey key, String playerId, int around) {
    List<?> result =
        redis.execute(
            playerRankScript,
            List.of(key.zsetKey(), key.metaKey(), PlayerNameCache.KEY),
            playerId,
            Integer.toString(around));
    State state = toState((String) result.get(0));
    if (state != State.READY || ((Number) result.get(1)).longValue() == 0) {
      return new Neighborhood(state, false, 0, 0, 0, 0, 0, List.of(), java.util.Map.of());
    }
    List<?> range = (List<?>) result.get(7);
    List<?> names = (List<?>) result.get(8);
    List<Member> members = new ArrayList<>(range.size() / 2);
    java.util.Map<String, String> nameById = new java.util.HashMap<>(range.size());
    for (int i = 0; i < range.size(); i += 2) {
      String id = (String) range.get(i);
      members.add(new Member(id, Double.parseDouble((String) range.get(i + 1))));
      if (names.get(i / 2) != null) {
        nameById.put(id, (String) names.get(i / 2));
      }
    }
    return new Neighborhood(
        state,
        true,
        Double.parseDouble((String) result.get(2)),
        ((Number) result.get(3)).longValue(),
        ((Number) result.get(4)).longValue(),
        ((Number) result.get(5)).longValue(),
        ((Number) result.get(6)).longValue(),
        members,
        nameById);
  }

  public State state(BoardKey key) {
    return toState(redis.opsForValue().get(key.metaKey()));
  }

  private static State toState(String meta) {
    if (READY.equals(meta)) {
      return State.READY;
    }
    return BUILDING.equals(meta) ? State.BUILDING : State.MISSING;
  }

  /**
   * Reads positions {@code start..stop} (best first) with names and ranks in one {@code EVALSHA}
   * (see {@code scripts/top_n.lua}). This is the hot path behind GetTopN.
   */
  public Page page(BoardKey key, long start, long stop) {
    List<?> result =
        redis.execute(
            topNScript,
            List.of(key.zsetKey(), key.metaKey(), PlayerNameCache.KEY, key.versionKey()),
            Long.toString(start),
            Long.toString(stop));
    State state = toState((String) result.get(0));
    if (state != State.READY) {
      return new Page(state, 0, 0, List.of(), java.util.Map.of(), 0);
    }
    List<?> range = (List<?>) result.get(3);
    List<?> names = (List<?>) result.get(4);
    List<Member> members = new ArrayList<>(range.size() / 2);
    java.util.Map<String, String> nameById = new java.util.HashMap<>(range.size());
    for (int i = 0; i < range.size(); i += 2) {
      String playerId = (String) range.get(i);
      members.add(new Member(playerId, Double.parseDouble((String) range.get(i + 1))));
      Object name = names.get(i / 2);
      if (name != null) {
        nameById.put(playerId, (String) name);
      }
    }
    return new Page(
        state,
        ((Number) result.get(1)).longValue(),
        ((Number) result.get(2)).longValue(),
        members,
        nameById,
        ((Number) result.get(5)).longValue());
  }

  /** Positions {@code start..stop} (0-based, best first). */
  public List<Member> range(BoardKey key, long start, long stop) {
    return toMembers(redis.opsForZSet().reverseRangeWithScores(key.zsetKey(), start, stop));
  }

  public long size(BoardKey key) {
    Long size = redis.opsForZSet().zCard(key.zsetKey());
    return size == null ? 0 : size;
  }

  public Optional<Double> rankingScore(BoardKey key, String playerId) {
    return Optional.ofNullable(redis.opsForZSet().score(key.zsetKey(), playerId));
  }

  /** Ranking scores of several players in one {@code ZMSCORE}; null where a player is unranked. */
  public List<Double> rankingScores(BoardKey key, List<String> playerIds) {
    if (playerIds.isEmpty()) {
      return List.of();
    }
    return redis.opsForZSet().score(key.zsetKey(), playerIds.toArray());
  }

  /** 0-based position of a player, best first ({@code ZREVRANK}). */
  public Optional<Long> position(BoardKey key, String playerId) {
    return Optional.ofNullable(redis.opsForZSet().reverseRank(key.zsetKey(), playerId));
  }

  /**
   * Competition rank of a ranking score: 1 + members with a strictly higher score. {@code
   * Math.nextUp} turns Redis' inclusive {@code ZCOUNT} bound into an exclusive one.
   */
  public long rankOf(BoardKey key, double rankingScore) {
    Long higher =
        redis
            .opsForZSet()
            .count(key.zsetKey(), Math.nextUp(rankingScore), Double.POSITIVE_INFINITY);
    return (higher == null ? 0 : higher) + 1;
  }

  /**
   * Atomically records a best score with {@code ZADD GT} and returns the rank before and after (see
   * {@code scripts/record_score.lua}).
   */
  public RankChange recordScore(
      BoardKey key, String playerId, double rankingScore, Optional<Instant> expireAt) {
    List<?> result =
        redis.execute(
            recordScoreScript,
            List.of(key.zsetKey(), key.metaKey(), key.versionKey()),
            playerId,
            toArg(rankingScore),
            String.valueOf(expireAt.map(Instant::getEpochSecond).orElse(0L)));
    return new RankChange(
        ((Number) result.get(0)).longValue() == 1,
        ((Number) result.get(1)).longValue(),
        ((Number) result.get(2)).longValue());
  }

  /**
   * Raises several members' scores in one pipelined {@code ZADD GT} (used to repair drift). GT
   * means a value read from Postgres a moment ago can never lower a newer score.
   */
  public void raiseScores(BoardKey key, java.util.Map<String, Double> rankingScores) {
    if (rankingScores.isEmpty()) {
      return;
    }
    java.util.Set<org.springframework.data.redis.connection.zset.Tuple> tuples =
        new java.util.LinkedHashSet<>();
    rankingScores.forEach(
        (member, score) ->
            tuples.add(
                new org.springframework.data.redis.connection.zset.DefaultTuple(
                    bytes(member), score)));
    byte[] zset = bytes(key.zsetKey());
    executePipelined(
        connection ->
            connection
                .zSetCommands()
                .zAdd(
                    zset,
                    tuples,
                    org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs.empty()
                        .gt()));
  }

  /** Formats a whole-number score without a decimal point or exponent, as Redis expects. */
  static String toArg(double rankingScore) {
    return Long.toString(Math.round(rankingScore));
  }

  private static List<Member> toMembers(Set<TypedTuple<String>> tuples) {
    if (tuples == null) {
      return List.of();
    }
    List<Member> members = new ArrayList<>(tuples.size());
    for (TypedTuple<String> tuple : tuples) {
      members.add(new Member(tuple.getValue(), tuple.getScore()));
    }
    return members;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Exposes raw connection access to the rebuilder, which needs pipelined {@code ZADD GT}. */
  <T> T execute(RedisCallback<T> callback) {
    return redis.execute(callback);
  }

  /** Same as {@link #execute} but pipelined. */
  List<Object> executePipelined(java.util.function.Consumer<RedisConnection> commands) {
    return redis.executePipelined(
        (RedisCallback<Object>)
            connection -> {
              commands.accept(connection);
              return null;
            });
  }
}
