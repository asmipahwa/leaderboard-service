package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.LeaderboardCache;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.RankChange;
import com.asmipahwa.leaderboard.cache.PlayerNameCache;
import com.asmipahwa.leaderboard.cache.Reconciler;
import com.asmipahwa.leaderboard.cache.RedisGuard;
import com.asmipahwa.leaderboard.error.ConflictException;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.service.ScoreWriter.WriteOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Write path for scores: validate, commit to Postgres (retrying lost optimistic-lock races), then
 * update the Redis ranking.
 *
 * <p>Order matters. Postgres commits first because it is the source of truth; Redis is only touched
 * once the data is safe. If Redis then fails, the score is still recorded, the player gets a rank
 * computed from Postgres, and the {@link Reconciler} repairs the cache later. The opposite order
 * could show players a rank for a score that was never saved.
 */
@Service
public class ScoreSubmissionService {

  /** Retries after the first attempt before giving up with {@code ABORTED}. */
  static final int MAX_RETRIES = 5;

  private static final long BASE_BACKOFF_MILLIS = 5;

  /** Everything a client sends for one match result. */
  public record SubmitCommand(
      String gameId,
      String leaderboardId,
      String periodKey,
      String playerId,
      long score,
      String idempotencyKey,
      boolean win,
      String platform) {

    /** Constructor for callers that predate the optional platform field. */
    public SubmitCommand(
        String gameId,
        String leaderboardId,
        String periodKey,
        String playerId,
        long score,
        String idempotencyKey,
        boolean win) {
      this(gameId, leaderboardId, periodKey, playerId, score, idempotencyKey, win, null);
    }
  }

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
  private final IdempotencyService idempotency;
  private final PeriodKeyResolver periods;
  private final LiveLeaderboardHub hub;
  private final RedisGuard redisGuard;
  private final Reconciler reconciler;
  private final SubmissionBulkhead bulkhead;
  private final MeterRegistry meters;
  private final Counter lockRetries;
  private final Timer dbTimer;
  private final Clock clock;

  public ScoreSubmissionService(
      LeaderboardRegistry registry,
      ScoreWriter writer,
      LeaderboardCache cache,
      LeaderboardQueryService queries,
      PlayerNameCache names,
      IdempotencyService idempotency,
      PeriodKeyResolver periods,
      LiveLeaderboardHub hub,
      RedisGuard redisGuard,
      Reconciler reconciler,
      SubmissionBulkhead bulkhead,
      MeterRegistry meters,
      Clock clock) {
    this.registry = registry;
    this.writer = writer;
    this.cache = cache;
    this.queries = queries;
    this.names = names;
    this.idempotency = idempotency;
    this.periods = periods;
    this.hub = hub;
    this.redisGuard = redisGuard;
    this.reconciler = reconciler;
    this.bulkhead = bulkhead;
    this.meters = meters;
    this.lockRetries =
        Counter.builder("leaderboard.optimistic.lock.retries")
            .description("Score writes retried after losing an optimistic-lock race")
            .register(meters);
    this.dbTimer =
        Timer.builder("leaderboard.db")
            .description("Score submission transaction time in Postgres (per attempt)")
            .tag("operation", "submit_transaction")
            .publishPercentileHistogram()
            .register(meters);
    this.clock = clock;
  }

  public SubmitResult submit(SubmitCommand command) {
    return bulkhead.run(() -> submitNow(command));
  }

  private SubmitResult submitNow(SubmitCommand command) {
    String gameId = Validation.id(command.gameId(), "game_id");
    String leaderboardId = Validation.id(command.leaderboardId(), "leaderboard_id");
    String requestedPeriod = Validation.period(command.periodKey());
    UUID playerId = Validation.playerId(command.playerId());
    long score = Validation.score(command.score());
    String idempotencyKey = Validation.idempotencyKey(command.idempotencyKey());
    String platform = Validation.platform(command.platform());

    // Scores always go to the current period; an old weekly board is read-only.
    ResolvedBoard board = registry.resolve(gameId, leaderboardId, null);
    if (!requestedPeriod.isEmpty() && !requestedPeriod.equals(board.key().periodKey())) {
      throw new InvalidRequestException("Scores can only be submitted to the current period");
    }

    Optional<SubmitResult> previous = idempotency.previousResult(idempotencyKey);
    if (previous.isPresent()) {
      count(gameId, "duplicate");
      return previous.get();
    }
    if (names.nameOf(playerId) == null) {
      throw new NotFoundException("Unknown player " + playerId);
    }

    WriteOutcome outcome =
        writeWithRetry(board, playerId, score, command.win(), idempotencyKey, platform);
    RankChange ranks = updateRanking(board, playerId, outcome.bestScore());
    if (outcome.improved() && !outcome.duplicate()) {
      hub.markChanged(board.key());
    }
    SubmitResult result =
        new SubmitResult(
            outcome.improved(),
            outcome.bestScore(),
            ranks.newRank(),
            ranks.previousRank(),
            outcome.unlockedAchievements());
    if (outcome.duplicate()) {
      count(gameId, "duplicate");
    } else {
      idempotency.remember(idempotencyKey, result);
      count(gameId, outcome.improved() ? "improved" : "not_improved");
    }
    return result;
  }

  /**
   * Runs the transaction, retrying when another request changed the same row first. Each retry
   * re-reads the row, so it compares against the latest best score. Waits grow exponentially with
   * random jitter ("full jitter") so competing requests don't retry in lock-step.
   */
  private WriteOutcome writeWithRetry(
      ResolvedBoard board,
      UUID playerId,
      long score,
      boolean win,
      String idempotencyKey,
      String platform) {
    for (int retry = 0; ; retry++) {
      Timer.Sample sample = Timer.start(meters);
      try {
        return writer.write(board, playerId, score, win, idempotencyKey, platform, clock.instant());
      } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
        if (retry == MAX_RETRIES) {
          meters.counter("leaderboard.optimistic.lock.exhausted").increment();
          throw new ConflictException(
              "Score update kept conflicting with concurrent updates; retry the request", e);
        }
        lockRetries.increment();
        backoff(retry);
      } finally {
        sample.stop(dbTimer);
      }
    }
  }

  private static void backoff(int retry) {
    long maxWait = BASE_BACKOFF_MILLIS << retry; // 5, 10, 20, 40, 80 ms
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(maxWait + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConflictException("Interrupted while retrying", e);
    }
  }

  private RankChange updateRanking(ResolvedBoard board, UUID playerId, long bestScore) {
    double rankingScore = board.sortOrder().toRankingScore(bestScore);
    Optional<Instant> expiry =
        periods.cacheExpiry(board.definition().getResetPeriod(), board.key().periodKey());
    return redisGuard.call(
        "record_score",
        () -> {
          RankChange change =
              cache.recordScore(board.key(), playerId.toString(), rankingScore, expiry);
          if (!change.cached()) {
            // Board not in Redis yet: build it from Postgres, which already contains this score.
            queries.ensureReady(board);
            change = cache.recordScore(board.key(), playerId.toString(), rankingScore, expiry);
          }
          return change;
        },
        () -> {
          // The score is safe in Postgres; repair the cache later and rank from the database now.
          reconciler.markDirty(board);
          return new RankChange(false, 0, queries.rankFromDatabase(board, bestScore));
        });
  }

  private void count(String gameId, String result) {
    meters.counter("leaderboard.score.submissions", "game", gameId, "result", result).increment();
  }
}
