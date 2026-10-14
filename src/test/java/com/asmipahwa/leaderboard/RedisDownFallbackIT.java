package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.cache.Reconciler;
import com.asmipahwa.leaderboard.cache.RedisGuard;
import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.GetFriendsLeaderboardRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreResponse;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Redis stops answering (container paused): reads keep working from Postgres, writes keep being
 * accepted, the circuit breaker opens, and once Redis is back the reconciler catches the cache up.
 */
class RedisDownFallbackIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired RedisGuard guard;
  @Autowired Reconciler reconciler;
  @Autowired MeterRegistry meters;

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub stub =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  private SubmitScoreResponse submit(Board board, UUID player, long score) {
    return stub.submitScore(
        SubmitScoreRequest.newBuilder()
            .setLeaderboard(board.ref())
            .setPlayerId(player.toString())
            .setScore(score)
            .setIdempotencyKey(UUID.randomUUID().toString())
            .build());
  }

  @Test
  void leaderboardStaysAvailableWhileRedisIsDown() throws Exception {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 30);
    TestData.befriend(jdbc, players.get(0), players.get(1));
    for (int i = 0; i < players.size(); i++) {
      submit(board, players.get(i), 100L * ((i % 10) + 1)); // ties in groups of three
    }
    GetTopNRequest top = GetTopNRequest.newBuilder().setLeaderboard(board.ref()).setN(10).build();
    GetPlayerRankRequest rank =
        GetPlayerRankRequest.newBuilder()
            .setLeaderboard(board.ref())
            .setPlayerId(players.get(14).toString())
            .build();
    GetTopNResponse topBefore = stub.getTopN(top);
    GetPlayerRankResponse rankBefore = stub.getPlayerRank(rank);
    double fallbacksBefore = fallbacks();
    // Start from a clean breaker so successful calls from earlier tests don't dilute the window.
    guard.reset();

    pauseRedis();
    try {
      // The breaker judges the last 10 s of Redis calls and needs at least 100 of them.
      List<Future<GetTopNResponse>> reads = new ArrayList<>();
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 400; i++) {
          reads.add(executor.submit(() -> stub.getTopN(top)));
        }
        for (Future<GetTopNResponse> read : reads) {
          assertThat(read.get().getEntriesList()).isEqualTo(topBefore.getEntriesList());
        }
      }
      // OPEN, or already HALF_OPEN (probing again) if the 5 s cool-down elapsed: either way it
      // tripped instead of letting every request wait on a dead Redis.
      assertThat(guard.state()).isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
      assertThat(stub.getPlayerRank(rank)).isEqualTo(rankBefore);
      assertThat(
              stub.getFriendsLeaderboard(
                      GetFriendsLeaderboardRequest.newBuilder()
                          .setLeaderboard(board.ref())
                          .setPlayerId(players.get(0).toString())
                          .build())
                  .getEntriesCount())
          .isEqualTo(2);

      // Writes still land in Postgres; the rank comes from SQL.
      SubmitScoreResponse write = submit(board, players.get(5), 5_000);
      assertThat(write.getImproved()).isTrue();
      assertThat(write.getRank()).isEqualTo(1);
      assertThat(fallbacks()).isGreaterThan(fallbacksBefore);
    } finally {
      unpauseRedis();
      guard.reset();
    }

    // Redis is back: the reconciler rebuilds the board that missed a write.
    reconciler.reconcile();
    assertThat(
            redis
                .opsForZSet()
                .score("lb:" + board.gameId() + ":board:all", players.get(5).toString()))
        .isEqualTo(5_000.0);
    assertThat(stub.getTopN(top).getEntries(0).getPlayerId()).isEqualTo(players.get(5).toString());
  }

  private double fallbacks() {
    return meters.find("leaderboard.redis.fallback").counters().stream()
        .mapToDouble(c -> c.count())
        .sum();
  }

  private static void pauseRedis() {
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
  }

  private static void unpauseRedis() {
    REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
  }
}
