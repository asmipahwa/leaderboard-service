package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.LeaderboardCache;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.RankChange;
import com.asmipahwa.leaderboard.cache.PlayerNameCache;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.service.ScoreWriter.WriteOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Write path for scores: validate, commit to Postgres, then update the Redis ranking.
 *
 * <p>Order matters. Postgres commits first because it is the source of truth; Redis is only touched
 * once the data is safe. If Redis then fails, the score is still recorded and the cache catches up
 * through a rebuild or the reconciler. The opposite order could show players a rank for a score
 * that was never saved.
 */
@Service
public class ScoreSubmissionService {

  /** Everything a client sends for one match result. */
  public record SubmitCommand(
      String gameId,
      String leaderboardId,
      String periodKey,
      String playerId,
      long score,
      String idempotencyKey,
      boolean win) {}

  /** What the player sees after submitting. */
  public record SubmitResult(
      boolean improved,
      long bestScore,
      long rank,
      long previousRank,
      List<String> unlockedAchievements) {}

  private final LeaderboardRegistry registry;
  private final ScoreWriter writer;
  private final LeaderboardCache cache;
  private final LeaderboardQueryService queries;
  private final PlayerNameCache names;
  private final PeriodKeyResolver periods;
  private final LiveLeaderboardHub hub;
  private final Clock clock;

  public ScoreSubmissionService(
      LeaderboardRegistry registry,
      ScoreWriter writer,
      LeaderboardCache cache,
      LeaderboardQueryService queries,
      PlayerNameCache names,
      PeriodKeyResolver periods,
      LiveLeaderboardHub hub,
      Clock clock) {
    this.registry = registry;
    this.writer = writer;
    this.cache = cache;
    this.queries = queries;
    this.names = names;
    this.periods = periods;
    this.hub = hub;
    this.clock = clock;
  }

  public SubmitResult submit(SubmitCommand command) {
    String gameId = Validation.id(command.gameId(), "game_id");
    String leaderboardId = Validation.id(command.leaderboardId(), "leaderboard_id");
    String requestedPeriod = Validation.period(command.periodKey());
    UUID playerId = Validation.playerId(command.playerId());
    long score = Validation.score(command.score());
    String idempotencyKey = Validation.idempotencyKey(command.idempotencyKey());

    // Scores always go to the current period; an old weekly board is read-only.
    ResolvedBoard board = registry.resolve(gameId, leaderboardId, null);
    if (!requestedPeriod.isEmpty() && !requestedPeriod.equals(board.key().periodKey())) {
      throw new InvalidRequestException("Scores can only be submitted to the current period");
    }
    if (names.nameOf(playerId) == null) {
      throw new NotFoundException("Unknown player " + playerId);
    }

    Instant now = clock.instant();
    WriteOutcome outcome = writer.write(board, playerId, score, command.win(), idempotencyKey, now);
    RankChange ranks = updateRanking(board, playerId, outcome.bestScore());
    if (outcome.improved()) {
      hub.markChanged(board.key());
    }
    return new SubmitResult(
        outcome.improved(), outcome.bestScore(), ranks.newRank(), ranks.previousRank(), List.of());
  }

  private RankChange updateRanking(ResolvedBoard board, UUID playerId, long bestScore) {
    double rankingScore = board.sortOrder().toRankingScore(bestScore);
    var expiry = periods.cacheExpiry(board.definition().getResetPeriod(), board.key().periodKey());
    RankChange change = cache.recordScore(board.key(), playerId.toString(), rankingScore, expiry);
    if (!change.cached()) {
      // Board not in Redis yet: build it from Postgres, which already contains this score.
      queries.ensureReady(board);
      change = cache.recordScore(board.key(), playerId.toString(), rankingScore, expiry);
    }
    return change;
  }
}
