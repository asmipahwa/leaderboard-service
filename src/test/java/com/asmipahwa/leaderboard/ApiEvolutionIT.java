package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the API evolved without breaking anyone: a client generated from the frozen original
 * contract ({@code src/test/proto/leaderboard/v1frozen}) still works against the server after
 * {@code SubmitScoreRequest.platform} and {@code GetTopNResponse.entries_version} were added.
 */
class ApiEvolutionIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;

  private final com.asmipahwa.leaderboard.grpc.v1frozen.LeaderboardServiceGrpc
          .LeaderboardServiceBlockingStub
      oldClient =
          com.asmipahwa.leaderboard.grpc.v1frozen.LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub newClient =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  private static com.asmipahwa.leaderboard.grpc.v1frozen.LeaderboardRef oldRef(Board board) {
    return com.asmipahwa.leaderboard.grpc.v1frozen.LeaderboardRef.newBuilder()
        .setGameId(board.gameId())
        .setLeaderboardId(board.leaderboardId())
        .build();
  }

  private String platformOf(String idempotencyKey) {
    return jdbc.queryForObject(
        "SELECT platform FROM score_events WHERE idempotency_key = ?",
        String.class,
        idempotencyKey);
  }

  @Test
  void clientBuiltAgainstOriginalV1StillWorks() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 2);
    String key = UUID.randomUUID().toString();

    var submitted =
        oldClient.submitScore(
            com.asmipahwa.leaderboard.grpc.v1frozen.SubmitScoreRequest.newBuilder()
                .setLeaderboard(oldRef(board))
                .setPlayerId(players.get(0).toString())
                .setScore(1_200)
                .setIdempotencyKey(key)
                .build());
    assertThat(submitted.getImproved()).isTrue();
    assertThat(submitted.getRank()).isEqualTo(1);
    assertThat(platformOf(key)).isNull(); // the old client never sends a platform

    // The new response carries entries_version (field 4); the old client just ignores it.
    var top =
        oldClient.getTopN(
            com.asmipahwa.leaderboard.grpc.v1frozen.GetTopNRequest.newBuilder()
                .setLeaderboard(oldRef(board))
                .setN(10)
                .build());
    assertThat(top.getEntriesCount()).isEqualTo(1);
    assertThat(top.getEntries(0).getScore()).isEqualTo(1_200);
    assertThat(top.getUnknownFields().hasField(4)).isTrue();
  }

  @Test
  void newClientSendsPlatformAndSeesEntriesVersionMove() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 2);
    GetTopNRequest top10 = GetTopNRequest.newBuilder().setLeaderboard(board.ref()).setN(10).build();

    String key = UUID.randomUUID().toString();
    newClient.submitScore(request(board, players.get(0), 500, key).setPlatform("ios").build());
    assertThat(platformOf(key)).isEqualTo("ios");
    GetTopNResponse first = newClient.getTopN(top10);

    // A score that doesn't improve anything leaves the version alone...
    newClient.submitScore(
        request(board, players.get(0), 100, UUID.randomUUID().toString()).build());
    assertThat(newClient.getTopN(top10).getEntriesVersion()).isEqualTo(first.getEntriesVersion());

    // ...an improvement moves it, so a polling client knows to re-render.
    newClient.submitScore(
        request(board, players.get(1), 900, UUID.randomUUID().toString()).build());
    assertThat(newClient.getTopN(top10).getEntriesVersion())
        .isGreaterThan(first.getEntriesVersion());
  }

  private static SubmitScoreRequest.Builder request(
      Board board, UUID player, long score, String key) {
    return SubmitScoreRequest.newBuilder()
        .setLeaderboard(board.ref())
        .setPlayerId(player.toString())
        .setScore(score)
        .setIdempotencyKey(key);
  }
}
