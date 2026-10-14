package com.asmipahwa.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.asmipahwa.leaderboard.cache.BoardKey;
import com.asmipahwa.leaderboard.cache.LeaderboardCache;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.RankChange;
import com.asmipahwa.leaderboard.cache.PlayerNameCache;
import com.asmipahwa.leaderboard.cache.Reconciler;
import com.asmipahwa.leaderboard.cache.RedisGuard;
import com.asmipahwa.leaderboard.config.LeaderboardProperties;
import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.error.ConflictException;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitCommand;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitResult;
import com.asmipahwa.leaderboard.service.ScoreWriter.WriteOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class ScoreSubmissionServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
  private static final UUID PLAYER = UUID.fromString("7f1c0f2e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");

  private final LeaderboardRegistry registry = mock(LeaderboardRegistry.class);
  private final ScoreWriter writer = mock(ScoreWriter.class);
  private final LeaderboardCache cache = mock(LeaderboardCache.class);
  private final LeaderboardQueryService queries = mock(LeaderboardQueryService.class);
  private final PlayerNameCache names = mock(PlayerNameCache.class);
  private final IdempotencyService idempotency = mock(IdempotencyService.class);
  private final LiveLeaderboardHub hub = mock(LiveLeaderboardHub.class);
  private final Reconciler reconciler = mock(Reconciler.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final PeriodKeyResolver periods =
      new PeriodKeyResolver(CLOCK, new LeaderboardProperties("S1"));
  private ScoreSubmissionService service;
  private ResolvedBoard board;

  @BeforeEach
  void setUp() {
    RedisGuard guard = new RedisGuard(meters, 50, 10, 5);
    service =
        new ScoreSubmissionService(
            registry,
            writer,
            cache,
            queries,
            names,
            idempotency,
            periods,
            hub,
            guard,
            reconciler,
            meters,
            CLOCK);
    board =
        new ResolvedBoard(
            new Leaderboard("fc-mobile", "high", "High", SortOrder.DESC, ResetPeriod.NONE),
            new BoardKey("fc-mobile", "high", "all"));
    when(registry.resolve("fc-mobile", "high", null)).thenReturn(board);
    when(names.nameOf(PLAYER)).thenReturn("Asmi");
    when(idempotency.previousResult(anyString())).thenReturn(Optional.empty());
  }

  private static SubmitCommand command(long score) {
    return new SubmitCommand("fc-mobile", "high", "", PLAYER.toString(), score, "key-1", true);
  }

  private static WriteOutcome outcome(boolean improved, long best) {
    return new WriteOutcome(improved, best, false, List.of());
  }

  private double retries() {
    return meters.counter("leaderboard.optimistic.lock.retries").count();
  }

  @Test
  void improvedScoreUpdatesRankingAndNotifiesSubscribers() {
    when(writer.write(eq(board), eq(PLAYER), eq(900L), eq(true), eq("key-1"), any()))
        .thenReturn(new WriteOutcome(true, 900, false, List.of("Pro")));
    when(cache.recordScore(eq(board.key()), eq(PLAYER.toString()), eq(900.0), any()))
        .thenReturn(new RankChange(true, 7, 2));

    SubmitResult result = service.submit(command(900));

    assertThat(result.improved()).isTrue();
    assertThat(result.bestScore()).isEqualTo(900);
    assertThat(result.rank()).isEqualTo(2);
    assertThat(result.previousRank()).isEqualTo(7);
    assertThat(result.unlockedAchievements()).containsExactly("Pro");
    verify(hub).markChanged(board.key());
    verify(idempotency).remember("key-1", result);
  }

  @Test
  void firstSubmissionHasNoPreviousRank() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenReturn(outcome(true, 300));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenReturn(new RankChange(true, 0, 12));

    SubmitResult result = service.submit(command(300));

    assertThat(result.previousRank()).isZero();
    assertThat(result.rank()).isEqualTo(12);
  }

  @Test
  void scoreThatDoesNotImproveKeepsBestAndSkipsNotification() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenReturn(outcome(false, 900));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenReturn(new RankChange(true, 2, 2));

    SubmitResult result = service.submit(command(100));

    assertThat(result.improved()).isFalse();
    assertThat(result.bestScore()).isEqualTo(900);
    verify(cache).recordScore(eq(board.key()), anyString(), eq(900.0), any());
    verify(hub, never()).markChanged(any());
  }

  @Test
  void lostOptimisticLockRaceIsRetriedWithFreshData() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException("Score", "id"))
        .thenThrow(new DataIntegrityViolationException("duplicate key scores_pkey"))
        .thenReturn(outcome(true, 900));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenReturn(new RankChange(true, 0, 1));

    SubmitResult result = service.submit(command(900));

    assertThat(result.improved()).isTrue();
    verify(writer, times(3)).write(any(), any(), anyLong(), anyBoolean(), anyString(), any());
    assertThat(retries()).isEqualTo(2);
  }

  @Test
  void givesUpWithConflictAfterFiveRetries() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException("Score", "id"));

    assertThatThrownBy(() -> service.submit(command(900))).isInstanceOf(ConflictException.class);

    verify(writer, times(1 + ScoreSubmissionService.MAX_RETRIES))
        .write(any(), any(), anyLong(), anyBoolean(), anyString(), any());
    assertThat(retries()).isEqualTo(ScoreSubmissionService.MAX_RETRIES);
    verify(cache, never()).recordScore(any(), anyString(), anyDouble(), any());
  }

  @Test
  void duplicateKeySeenInRedisReturnsStoredResultWithoutWriting() {
    SubmitResult stored = new SubmitResult(true, 900, 3, 10, List.of());
    when(idempotency.previousResult("key-1")).thenReturn(Optional.of(stored));

    assertThat(service.submit(command(900))).isEqualTo(stored);
    verify(writer, never()).write(any(), any(), anyLong(), anyBoolean(), anyString(), any());
  }

  @Test
  void duplicateDetectedInPostgresIsNotRememberedAgainOrBroadcast() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenReturn(new WriteOutcome(true, 900, true, List.of()));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenReturn(new RankChange(true, 1, 1));

    service.submit(command(900));

    verify(hub, never()).markChanged(any());
    verify(idempotency, never()).remember(anyString(), any());
    assertThat(
            meters
                .counter(
                    "leaderboard.score.submissions", "game", "fc-mobile", "result", "duplicate")
                .count())
        .isEqualTo(1);
  }

  @Test
  void coldBoardIsRebuiltThenRankedAgain() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenReturn(outcome(true, 500));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenReturn(new RankChange(false, 0, 0), new RankChange(true, 4, 4));

    SubmitResult result = service.submit(command(500));

    verify(queries).ensureReady(board);
    assertThat(result.rank()).isEqualTo(4);
  }

  @Test
  void redisDownAfterCommitStillSucceedsWithDatabaseRankAndMarksBoardDirty() {
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenReturn(outcome(true, 500));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenThrow(new RedisConnectionFailureException("down"));
    when(queries.rankFromDatabase(board, 500)).thenReturn(8L);

    SubmitResult result = service.submit(command(500));

    assertThat(result.improved()).isTrue();
    assertThat(result.rank()).isEqualTo(8);
    verify(reconciler).markDirty(board);
  }

  @Test
  void ascendingBoardSendsNegatedScoreToRedis() {
    ResolvedBoard lap =
        new ResolvedBoard(
            new Leaderboard("fc-mobile", "lap", "Lap", SortOrder.ASC, ResetPeriod.WEEKLY),
            new BoardKey("fc-mobile", "lap", "2026-W42"));
    when(registry.resolve("fc-mobile", "lap", null)).thenReturn(lap);
    when(writer.write(any(), any(), anyLong(), anyBoolean(), anyString(), any()))
        .thenReturn(outcome(true, 58_500));
    when(cache.recordScore(any(), anyString(), anyDouble(), any()))
        .thenReturn(new RankChange(true, 0, 1));

    service.submit(
        new SubmitCommand("fc-mobile", "lap", "", PLAYER.toString(), 58_500, "k", false));

    verify(cache).recordScore(eq(lap.key()), anyString(), eq(-58_500.0), any());
  }

  @Test
  void submittingToAPastPeriodIsRejected() {
    assertThatThrownBy(
            () ->
                service.submit(
                    new SubmitCommand(
                        "fc-mobile", "high", "2020-W01", PLAYER.toString(), 1, "k", false)))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void unknownPlayerIsNotFound() {
    when(names.nameOf(PLAYER)).thenReturn(null);
    assertThatThrownBy(() -> service.submit(command(1))).isInstanceOf(NotFoundException.class);
  }
}
