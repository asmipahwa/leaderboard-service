package com.asmipahwa.leaderboard.cache;

import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao.ScoreRow;
import com.asmipahwa.leaderboard.service.PeriodKeyResolver;
import com.asmipahwa.leaderboard.service.ResolvedBoard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.zset.DefaultTuple;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Rebuilds a leaderboard's Redis sorted set from Postgres, the source of truth.
 *
 * <p>Used on startup, on a cache miss (for example after Redis lost its data), and by operators.
 * The steps, and why each is safe while scores keep arriving:
 *
 * <ol>
 *   <li>Take a per-board lock ({@code SET NX PX}), so when 50 requests miss at once only one pod
 *       hits Postgres. Everyone else waits for the result. This prevents a cache stampede.
 *   <li>Delete the old set and mark the board {@code building}. From now on, live writes go into
 *       the new set, but readers don't trust it yet.
 *   <li>Stream the board from Postgres in pages and {@code ZADD GT} each page in a pipeline. GT
 *       means a stale row read from Postgres can never overwrite a newer score that a live write
 *       just added.
 *   <li>Mark the board {@code ready}.
 * </ol>
 */
@Component
public class LeaderboardRebuilder {

  private static final Logger log = LoggerFactory.getLogger(LeaderboardRebuilder.class);
  private static final int PAGE_SIZE = 5_000;
  private static final Duration LOCK_TTL = Duration.ofMinutes(2);

  private final StringRedisTemplate redis;
  private final LeaderboardCache cache;
  private final ScoreQueryDao scores;
  private final PlayerNameCache names;
  private final PeriodKeyResolver periods;
  private final RedisScript<Long> releaseLockScript;

  public LeaderboardRebuilder(
      StringRedisTemplate redis,
      LeaderboardCache cache,
      ScoreQueryDao scores,
      PlayerNameCache names,
      PeriodKeyResolver periods,
      RedisScript<Long> releaseLockScript) {
    this.redis = redis;
    this.cache = cache;
    this.scores = scores;
    this.names = names;
    this.periods = periods;
    this.releaseLockScript = releaseLockScript;
  }

  /**
   * Rebuilds the board if no other pod is already doing it.
   *
   * @return number of players loaded, or -1 if another rebuild holds the lock
   */
  public long rebuild(ResolvedBoard board) {
    BoardKey key = board.key();
    String token = UUID.randomUUID().toString();
    Boolean locked = redis.opsForValue().setIfAbsent(key.lockKey(), token, LOCK_TTL);
    if (!Boolean.TRUE.equals(locked)) {
      return -1;
    }
    long started = System.nanoTime();
    try {
      Optional<Instant> expiry =
          periods.cacheExpiry(board.definition().getResetPeriod(), key.periodKey());
      redis.delete(key.zsetKey());
      setMeta(key, LeaderboardCache.BUILDING, expiry);

      long[] loaded = {0};
      scores.forEachPage(
          key.gameId(),
          key.leaderboardId(),
          key.periodKey(),
          PAGE_SIZE,
          page -> loaded[0] += loadPage(key, board.sortOrder(), page));

      expiry.ifPresent(at -> redis.expireAt(key.zsetKey(), at));
      setMeta(key, LeaderboardCache.READY, expiry);
      log.info(
          "Rebuilt leaderboard {} with {} players in {} ms",
          key,
          loaded[0],
          (System.nanoTime() - started) / 1_000_000);
      return loaded[0];
    } catch (RuntimeException e) {
      // Remove the marker so writes stop landing in a half-built set and the next read retries.
      redis.delete(key.metaKey());
      throw e;
    } finally {
      redis.execute(releaseLockScript, List.of(key.lockKey()), token);
    }
  }

  private long loadPage(BoardKey key, SortOrder order, List<ScoreRow> page) {
    Set<Tuple> tuples = new LinkedHashSet<>(page.size() * 2);
    Map<String, String> pageNames = new HashMap<>(page.size() * 2);
    for (ScoreRow row : page) {
      String id = row.playerId().toString();
      tuples.add(new DefaultTuple(bytes(id), order.toRankingScore(row.bestScore())));
      pageNames.put(id, row.displayName());
    }
    byte[] zset = bytes(key.zsetKey());
    cache.executePipelined(
        connection -> connection.zSetCommands().zAdd(zset, tuples, ZAddArgs.empty().gt()));
    names.putAll(pageNames);
    return page.size();
  }

  private void setMeta(BoardKey key, String value, Optional<Instant> expiry) {
    redis.opsForValue().set(key.metaKey(), value);
    expiry.ifPresent(at -> redis.expireAt(key.metaKey(), at));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
