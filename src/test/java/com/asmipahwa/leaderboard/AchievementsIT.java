package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerStatsRequest;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.PlayerStatsServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Achievements unlock when a threshold is first reached, and only once. */
class AchievementsIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub stub =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  private List<String> submit(Board board, UUID player, long score) {
    return stub.submitScore(
            SubmitScoreRequest.newBuilder()
                .setLeaderboard(board.ref())
                .setPlayerId(player.toString())
                .setScore(score)
                .setIdempotencyKey(UUID.randomUUID().toString())
                .build())
        .getUnlockedAchievementsList();
  }

  @Test
  void thresholdsUnlockOnceInOrder() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    jdbc.update(
        "INSERT INTO achievements (game_id, name, threshold) VALUES (?, 'Rising Star', 1000),"
            + " (?, 'Pro', 2000)",
        board.gameId(),
        board.gameId());
    UUID player = TestData.players(jdbc, 1).get(0);

    assertThat(submit(board, player, 500)).isEmpty();
    assertThat(submit(board, player, 1_500)).containsExactly("Rising Star");
    assertThat(submit(board, player, 1_200)).isEmpty();
    assertThat(submit(board, player, 2_500)).containsExactly("Pro");
    assertThat(submit(board, player, 9_000)).isEmpty();

    assertThat(
            PlayerStatsServiceGrpc.newBlockingStub(CHANNEL)
                .getPlayerStats(
                    GetPlayerStatsRequest.newBuilder()
                        .setGameId(board.gameId())
                        .setPlayerId(player.toString())
                        .build())
                .getAchievementsList())
        .containsExactly("Rising Star", "Pro");
  }
}
