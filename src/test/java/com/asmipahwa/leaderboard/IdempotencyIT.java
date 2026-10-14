package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreResponse;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** A retried request (same idempotency key) must be counted exactly once. */
class IdempotencyIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub stub =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  private SubmitScoreRequest request(Board board, UUID player, String key) {
    return SubmitScoreRequest.newBuilder()
        .setLeaderboard(board.ref())
        .setPlayerId(player.toString())
        .setScore(4_200)
        .setWin(true)
        .setIdempotencyKey(key)
        .build();
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  void sameKeySentTenTimesIsRecordedOnce() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    UUID player = TestData.players(jdbc, 1).get(0);
    String key = UUID.randomUUID().toString();

    List<SubmitScoreResponse> responses = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      responses.add(stub.submitScore(request(board, player, key)));
    }

    assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(responses.get(0)));
    assertThat(responses.get(0).getImproved()).isTrue();
    assertThat(count("SELECT count(*) FROM score_events WHERE idempotency_key = ?", key))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT matches_played FROM player_stats WHERE game_id = ? AND player_id = ?",
                board.gameId(),
                player))
        .isEqualTo(1);
  }

  @Test
  void duplicateIsDetectedByPostgresEvenWhenRedisForgotTheKey() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    UUID player = TestData.players(jdbc, 1).get(0);
    String key = UUID.randomUUID().toString();

    stub.submitScore(request(board, player, key));
    redis.delete("idem:" + key);
    SubmitScoreResponse retry = stub.submitScore(request(board, player, key));

    assertThat(retry.getImproved()).isTrue(); // original outcome
    assertThat(count("SELECT count(*) FROM score_events WHERE idempotency_key = ?", key))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT wins FROM player_stats WHERE game_id = ? AND player_id = ?",
                board.gameId(),
                player))
        .isEqualTo(1);
  }

  @Test
  void concurrentCopiesOfTheSameRequestAreRecordedOnce() throws Exception {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    UUID player = TestData.players(jdbc, 1).get(0);
    String key = UUID.randomUUID().toString();
    CountDownLatch gate = new CountDownLatch(1);
    List<Future<SubmitScoreResponse>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        futures.add(
            executor.submit(
                () -> {
                  gate.await();
                  return stub.submitScore(request(board, player, key));
                }));
      }
      gate.countDown();
      for (Future<SubmitScoreResponse> future : futures) {
        assertThat(future.get().getBestScore()).isEqualTo(4_200);
      }
    }
    assertThat(count("SELECT count(*) FROM score_events WHERE idempotency_key = ?", key))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT matches_played FROM player_stats WHERE game_id = ? AND player_id = ?",
                board.gameId(),
                player))
        .isEqualTo(1);
  }
}
