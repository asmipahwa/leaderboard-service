package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves Postgres is the source of truth: wipe Redis completely and the next read rebuilds the
 * exact same leaderboard, names included.
 */
class RedisLossRecoveryIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub stub =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  @Test
  void flushAllThenReadRebuildsIdenticalLeaderboard() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 300);
    Random random = new Random(7);
    for (UUID player : players) {
      for (int match = 0; match < 2; match++) {
        stub.submitScore(
            SubmitScoreRequest.newBuilder()
                .setLeaderboard(board.ref())
                .setPlayerId(player.toString())
                .setScore(random.nextInt(2_000)) // small range => plenty of ties
                .setIdempotencyKey(UUID.randomUUID().toString())
                .build());
      }
    }
    GetTopNRequest top100 =
        GetTopNRequest.newBuilder().setLeaderboard(board.ref()).setN(100).build();
    GetTopNResponse before = stub.getTopN(top100);
    var rankBefore =
        stub.getPlayerRank(
            GetPlayerRankRequest.newBuilder()
                .setLeaderboard(board.ref())
                .setPlayerId(players.get(42).toString())
                .build());

    redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    assertThat(redis.hasKey("lb:" + board.gameId() + ":board:all")).isFalse();

    GetTopNResponse after = stub.getTopN(top100);
    assertThat(after.getEntriesList()).isEqualTo(before.getEntriesList());
    assertThat(after.getTotalPlayers()).isEqualTo(300);
    assertThat(
            stub.getPlayerRank(
                GetPlayerRankRequest.newBuilder()
                    .setLeaderboard(board.ref())
                    .setPlayerId(players.get(42).toString())
                    .build()))
        .isEqualTo(rankBefore);
  }
}
