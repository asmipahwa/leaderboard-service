package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Backs the resume claim "2,000+ concurrent score updates with zero lost writes".
 *
 * <p>2,500 score submissions are released at the same instant (virtual threads behind a {@link
 * CountDownLatch} start gate) through the real gRPC API. They all target the same 50 players, so
 * ~50 requests race on every player's row and optimistic-lock conflicts are guaranteed. Afterwards
 * we check that nothing was lost or double-counted: every player's best score in Postgres equals
 * the max they submitted, Redis matches Postgres exactly, every submission has exactly one event
 * row, and match/win counts add up.
 */
class ConcurrentSubmitIT extends IntegrationTestSupport {

  private static final int SUBMISSIONS = 2_500;
  private static final int PLAYERS = 50;

  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry meters;

  @Test
  void concurrentSubmissionsLoseNoWrites() throws Exception {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, PLAYERS);
    Random random = new Random(2026);
    long[] scores = new long[SUBMISSIONS];
    for (int i = 0; i < SUBMISSIONS; i++) {
      scores[i] = random.nextInt(1_000_000);
    }
    double retriesBefore = meters.counter("leaderboard.optimistic.lock.retries").count();

    LeaderboardServiceGrpc.LeaderboardServiceBlockingStub stub =
        LeaderboardServiceGrpc.newBlockingStub(CHANNEL);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger ready = new AtomicInteger();
    Map<Integer, Throwable> failures = new ConcurrentHashMap<>();
    List<Future<?>> futures = new ArrayList<>(SUBMISSIONS);
    long started;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < SUBMISSIONS; i++) {
        int n = i;
        futures.add(
            executor.submit(
                () -> {
                  ready.incrementAndGet();
                  startGate.await();
                  try {
                    stub.submitScore(
                        SubmitScoreRequest.newBuilder()
                            .setLeaderboard(board.ref())
                            .setPlayerId(players.get(n % PLAYERS).toString())
                            .setScore(scores[n])
                            .setWin(n % 3 == 0)
                            .setIdempotencyKey("concurrent-" + board.gameId() + "-" + n)
                            .build());
                  } catch (RuntimeException e) {
                    failures.put(n, e);
                  }
                  return null;
                }));
      }
      while (ready.get() < SUBMISSIONS) {
        Thread.onSpinWait();
      }
      started = System.nanoTime();
      startGate.countDown(); // release all 2,500 at once
      for (Future<?> future : futures) {
        future.get();
      }
    }
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    double retries = meters.counter("leaderboard.optimistic.lock.retries").count() - retriesBefore;

    // Expected state, computed from what we sent.
    Map<UUID, Long> expectedBest = new HashMap<>();
    Map<UUID, Long> expectedMatches = new HashMap<>();
    Map<UUID, Long> expectedWins = new HashMap<>();
    for (int i = 0; i < SUBMISSIONS; i++) {
      UUID player = players.get(i % PLAYERS);
      expectedBest.merge(player, scores[i], Math::max);
      expectedMatches.merge(player, 1L, Long::sum);
      expectedWins.merge(player, i % 3 == 0 ? 1L : 0L, Long::sum);
    }

    int lostWrites = 0;
    for (UUID player : players) {
      long dbBest =
          jdbc.queryForObject(
              "SELECT best_score FROM scores WHERE game_id = ? AND leaderboard_id = ?"
                  + " AND period_key = 'all' AND player_id = ?",
              Long.class,
              board.gameId(),
              board.leaderboardId(),
              player);
      Double redisBest =
          redis.opsForZSet().score("lb:" + board.gameId() + ":board:all", player.toString());
      Map<String, Object> stats =
          jdbc.queryForMap(
              "SELECT matches_played, wins FROM player_stats WHERE game_id = ? AND player_id = ?",
              board.gameId(),
              player);
      boolean ok =
          dbBest == expectedBest.get(player)
              && redisBest != null
              && redisBest.longValue() == dbBest
              && ((Number) stats.get("matches_played")).longValue() == expectedMatches.get(player)
              && ((Number) stats.get("wins")).longValue() == expectedWins.get(player);
      if (!ok) {
        lostWrites++;
      }
    }
    long events =
        jdbc.queryForObject(
            "SELECT count(*) FROM score_events WHERE game_id = ?", Long.class, board.gameId());

    String summary =
        String.format(
            "ConcurrentSubmitIT: submissions=%d released-at-once players=%d failed-requests=%d"
                + " event-rows=%d lost-writes=%d optimistic-lock-retries=%.0f elapsed-ms=%d",
            SUBMISSIONS, PLAYERS, failures.size(), events, lostWrites, retries, elapsedMillis);
    System.out.println(summary);
    writeEvidence(summary);

    assertThat(failures).as("failed submissions").isEmpty();
    assertThat(events).as("one event row per submission").isEqualTo(SUBMISSIONS);
    assertThat(lostWrites).as("players whose final state is wrong").isZero();
    assertThat(retries).as("optimistic locking actually had to resolve conflicts").isPositive();
  }

  private static void writeEvidence(String summary) throws IOException {
    Path out = Path.of("build", "evidence", "concurrent-submit-it.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, summary + System.lineSeparator());
  }
}
