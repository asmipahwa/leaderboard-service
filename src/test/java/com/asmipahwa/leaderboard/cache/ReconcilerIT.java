package com.asmipahwa.leaderboard.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.service.LeaderboardRegistry;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** The reconciler notices Redis scores that fell behind Postgres and repairs them. */
class ReconcilerIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired Reconciler reconciler;
  @Autowired LeaderboardRegistry registry;

  @Test
  void driftedScoresAreRepaired() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 20);
    var stub = LeaderboardServiceGrpc.newBlockingStub(CHANNEL);
    for (int i = 0; i < players.size(); i++) {
      stub.submitScore(
          SubmitScoreRequest.newBuilder()
              .setLeaderboard(board.ref())
              .setPlayerId(players.get(i).toString())
              .setScore(1_000 + i)
              .setIdempotencyKey(UUID.randomUUID().toString())
              .build());
    }
    String zset = "lb:" + board.gameId() + ":board:all";
    // Simulate drift: one player vanished from Redis, another has a stale (lower) score.
    redis.opsForZSet().remove(zset, players.get(3).toString());
    redis.opsForZSet().add(zset, players.get(7).toString(), 1);

    long fixed =
        reconciler.sampleAndFix(registry.resolve(board.gameId(), "board", null), new UUID(0, 0));

    assertThat(fixed).isEqualTo(2);
    assertThat(redis.opsForZSet().score(zset, players.get(3).toString())).isEqualTo(1_003.0);
    assertThat(redis.opsForZSet().score(zset, players.get(7).toString())).isEqualTo(1_007.0);
  }
}
