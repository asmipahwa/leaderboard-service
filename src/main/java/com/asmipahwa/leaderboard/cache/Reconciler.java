package com.asmipahwa.leaderboard.cache;

import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.repository.LeaderboardRepository;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao.ScoreRow;
import com.asmipahwa.leaderboard.service.LeaderboardRegistry;
import com.asmipahwa.leaderboard.service.ResolvedBoard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that keeps Redis honest against Postgres.
 *
 * <p>Two jobs, every minute by default:
 *
 * <ol>
 *   <li>Boards whose Redis update failed after a commit (Redis was down) are marked dirty by the
 *       write path and fully rebuilt here once Redis is reachable again.
 *   <li>For every ready board, a sample of players is compared with Postgres, and any score that
 *       Redis is missing or has too low is fixed with {@code ZADD GT}. That catches drift nobody
 *       noticed, and {@code leaderboard_drift_fixed_total} makes it visible on a dashboard.
 * </ol>
 */
@Component
public class Reconciler {

  private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

  private final LeaderboardRepository leaderboards;
  private final LeaderboardRegistry registry;
  private final LeaderboardCache cache;
  private final LeaderboardRebuilder rebuilder;
  private final ScoreQueryDao scores;
  private final PlayerNameCache names;
  private final Counter driftFixed;
  private final boolean enabled;
  private final int sampleSize;
  private final Map<BoardKey, ResolvedBoard> dirty = new ConcurrentHashMap<>();

  public Reconciler(
      LeaderboardRepository leaderboards,
      LeaderboardRegistry registry,
      LeaderboardCache cache,
      LeaderboardRebuilder rebuilder,
      ScoreQueryDao scores,
      PlayerNameCache names,
      MeterRegistry meters,
      @Value("${leaderboard.reconciler.enabled:true}") boolean enabled,
      @Value("${leaderboard.reconciler.sample-size:200}") int sampleSize) {
    this.leaderboards = leaderboards;
    this.registry = registry;
    this.cache = cache;
    this.rebuilder = rebuilder;
    this.scores = scores;
    this.names = names;
    this.driftFixed =
        Counter.builder("leaderboard.drift.fixed")
            .description("Redis scores repaired from Postgres by the reconciler")
            .register(meters);
    this.enabled = enabled;
    this.sampleSize = sampleSize;
  }

  /** Called by the write path when it committed a score but could not update Redis. */
  public void markDirty(ResolvedBoard board) {
    dirty.put(board.key(), board);
  }

  public boolean isDirty(BoardKey key) {
    return dirty.containsKey(key);
  }

  @Scheduled(
      fixedDelayString = "${leaderboard.reconciler.interval-ms:60000}",
      initialDelayString = "${leaderboard.reconciler.interval-ms:60000}")
  public void scheduledRun() {
    if (enabled) {
      reconcile();
    }
  }

  /**
   * One reconciliation pass.
   *
   * @return number of drifted scores fixed by sampling
   */
  public long reconcile() {
    for (ResolvedBoard board : new ArrayList<>(dirty.values())) {
      try {
        if (rebuilder.rebuild(board) >= 0) {
          dirty.remove(board.key());
        }
      } catch (DataAccessException e) {
        log.warn("Reconciler could not rebuild dirty board {} yet", board.key());
      }
    }
    long fixed = 0;
    for (Leaderboard definition : leaderboards.findAll()) {
      ResolvedBoard board =
          registry.resolve(definition.getGameId(), definition.getLeaderboardId(), null);
      try {
        if (cache.state(board.key()) == LeaderboardCache.State.READY) {
          fixed += sampleAndFix(board);
        }
      } catch (DataAccessException e) {
        log.warn("Reconciler skipped {}: {}", board.key(), e.getMessage());
      }
    }
    return fixed;
  }

  long sampleAndFix(ResolvedBoard board) {
    return sampleAndFix(board, UUID.randomUUID());
  }

  /** Checks up to {@code sampleSize} players starting at player id {@code from}. */
  long sampleAndFix(ResolvedBoard board, UUID from) {
    BoardKey key = board.key();
    List<ScoreRow> rows =
        scores.sample(key.gameId(), key.leaderboardId(), key.periodKey(), from, sampleSize);
    if (rows.isEmpty()) {
      return 0;
    }
    List<String> ids = rows.stream().map(row -> row.playerId().toString()).toList();
    List<Double> cached = cache.rankingScores(key, ids);
    SortOrder order = board.sortOrder();
    Map<String, Double> repairs = new HashMap<>();
    Map<String, String> repairedNames = new HashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      double expected = order.toRankingScore(rows.get(i).bestScore());
      Double actual = cached.get(i);
      // Redis can only legitimately be behind Postgres (it is written after commit), never ahead.
      if (actual == null || actual < expected) {
        repairs.put(ids.get(i), expected);
        repairedNames.put(ids.get(i), rows.get(i).displayName());
      }
    }
    if (!repairs.isEmpty()) {
      cache.raiseScores(key, repairs);
      names.putAll(repairedNames);
      driftFixed.increment(repairs.size());
      log.info("Reconciler fixed {} drifted scores on {}", repairs.size(), key);
    }
    return repairs.size();
  }
}
