package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.GetFriendsLeaderboardRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerStatsRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetScoreHistoryRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetScoreHistoryResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardEntry;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardRef;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.PlayerStats;
import com.asmipahwa.leaderboard.grpc.v1.PlayerStatsServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.StreamTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreResponse;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end through the real gRPC API (in-process), Postgres and Redis: submit scores, then read
 * them back through every leaderboard RPC.
 */
class SubmitAndQueryIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub leaderboards =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);
  private final PlayerStatsServiceGrpc.PlayerStatsServiceBlockingStub stats =
      PlayerStatsServiceGrpc.newBlockingStub(CHANNEL);

  private SubmitScoreResponse submit(LeaderboardRef ref, UUID player, long score, boolean win) {
    return leaderboards.submitScore(
        SubmitScoreRequest.newBuilder()
            .setLeaderboard(ref)
            .setPlayerId(player.toString())
            .setScore(score)
            .setIdempotencyKey(UUID.randomUUID().toString())
            .setWin(win)
            .build());
  }

  private GetTopNResponse top(LeaderboardRef ref, int n, int offset) {
    return leaderboards.getTopN(
        GetTopNRequest.newBuilder().setLeaderboard(ref).setN(n).setOffset(offset).build());
  }

  /** 50 players; scores come in tied pairs (players 0/1 share a score, 2/3 share one, ...). */
  private static long pairedScore(int i) {
    return 1_000 + (i / 2) * 10L;
  }

  @Test
  void topNIsOrderedBestFirstWithCompetitionRanking() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 50);
    for (int i = 0; i < players.size(); i++) {
      submit(board.ref(), players.get(i), pairedScore(i), false);
    }

    GetTopNResponse top = top(board.ref(), 10, 0);
    assertThat(top.getTotalPlayers()).isEqualTo(50);
    assertThat(top.getEntriesList())
        .extracting(LeaderboardEntry::getScore)
        .containsExactly(1240L, 1240L, 1230L, 1230L, 1220L, 1220L, 1210L, 1210L, 1200L, 1200L);
    // Ties share a rank and the next rank is skipped: 1, 1, 3, 3, 5, 5...
    assertThat(top.getEntriesList())
        .extracting(LeaderboardEntry::getRank)
        .containsExactly(1L, 1L, 3L, 3L, 5L, 5L, 7L, 7L, 9L, 9L);
    assertThat(top.getEntries(0).getDisplayName()).startsWith("Player");
  }

  @Test
  void pageStartingInsideATieKeepsTheTiedRank() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 50);
    for (int i = 0; i < players.size(); i++) {
      submit(board.ref(), players.get(i), pairedScore(i), false);
    }
    // Positions 4 and 5 share rank 5. A page that starts at position 5 must still say rank 5.
    GetTopNResponse page = top(board.ref(), 3, 5);
    assertThat(page.getEntriesList())
        .extracting(LeaderboardEntry::getRank)
        .containsExactly(5L, 7L, 7L);
  }

  @Test
  void submitReportsImprovementAndRankChanges() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 3);
    submit(board.ref(), players.get(0), 500, false);
    submit(board.ref(), players.get(1), 400, false);

    SubmitScoreResponse first = submit(board.ref(), players.get(2), 300, false);
    assertThat(first.getImproved()).isTrue();
    assertThat(first.getRank()).isEqualTo(3);
    assertThat(first.getPreviousRank()).isZero();

    SubmitScoreResponse worse = submit(board.ref(), players.get(2), 100, false);
    assertThat(worse.getImproved()).isFalse();
    assertThat(worse.getBestScore()).isEqualTo(300);
    assertThat(worse.getRank()).isEqualTo(3);

    SubmitScoreResponse better = submit(board.ref(), players.get(2), 900, true);
    assertThat(better.getImproved()).isTrue();
    assertThat(better.getBestScore()).isEqualTo(900);
    assertThat(better.getPreviousRank()).isEqualTo(3);
    assertThat(better.getRank()).isEqualTo(1);
  }

  @Test
  void ascendingBoardRanksTheLowestTimeFirst() {
    Board board = TestData.board(jdbc, SortOrder.ASC, ResetPeriod.WEEKLY);
    List<UUID> players = TestData.players(jdbc, 3);
    submit(board.ref(), players.get(0), 62_000, false);
    submit(board.ref(), players.get(1), 58_500, false);
    submit(board.ref(), players.get(2), 70_100, false);
    assertThat(submit(board.ref(), players.get(2), 75_000, false).getImproved()).isFalse();

    assertThat(top(board.ref(), 10, 0).getEntriesList())
        .extracting(LeaderboardEntry::getScore)
        .containsExactly(58_500L, 62_000L, 70_100L);
  }

  @Test
  void playerRankReturnsNeighboursAndPercentile() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 20);
    for (int i = 0; i < players.size(); i++) {
      submit(board.ref(), players.get(i), 100L * (i + 1), false); // player 19 is best
    }
    GetPlayerRankResponse rank =
        leaderboards.getPlayerRank(
            GetPlayerRankRequest.newBuilder()
                .setLeaderboard(board.ref())
                .setPlayerId(players.get(9).toString())
                .setAround(2)
                .build());

    assertThat(rank.getMe().getRank()).isEqualTo(11);
    assertThat(rank.getMe().getScore()).isEqualTo(1_000);
    assertThat(rank.getAboveList()).extracting(LeaderboardEntry::getRank).containsExactly(9L, 10L);
    assertThat(rank.getBelowList()).extracting(LeaderboardEntry::getRank).containsExactly(12L, 13L);
    assertThat(rank.getPercentile()).isEqualTo(1.0 - 11.0 / 20);
  }

  @Test
  void topOfBoardHasNobodyAbove() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 5);
    for (int i = 0; i < players.size(); i++) {
      submit(board.ref(), players.get(i), 10L * (i + 1), false);
    }
    GetPlayerRankResponse rank =
        leaderboards.getPlayerRank(
            GetPlayerRankRequest.newBuilder()
                .setLeaderboard(board.ref())
                .setPlayerId(players.get(4).toString())
                .build());
    assertThat(rank.getMe().getRank()).isEqualTo(1);
    assertThat(rank.getAboveList()).isEmpty();
    assertThat(rank.getBelowList()).hasSize(2);
  }

  @Test
  void friendsLeaderboardRanksOnlyFriendsAndMe() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 6);
    UUID me = players.get(0);
    TestData.befriend(jdbc, me, players.get(1));
    TestData.befriend(jdbc, players.get(2), me);
    for (int i = 0; i < players.size(); i++) {
      submit(board.ref(), players.get(i), 100L * (6 - i), false);
    }

    GetTopNResponse friends =
        leaderboards.getFriendsLeaderboard(
            GetFriendsLeaderboardRequest.newBuilder()
                .setLeaderboard(board.ref())
                .setPlayerId(me.toString())
                .build());
    assertThat(friends.getEntriesList())
        .extracting(LeaderboardEntry::getPlayerId)
        .containsExactly(me.toString(), players.get(1).toString(), players.get(2).toString());
    assertThat(friends.getEntriesList())
        .extracting(LeaderboardEntry::getRank)
        .containsExactly(1L, 2L, 3L);
  }

  @Test
  void statsAndHistoryTrackEveryMatch() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    UUID player = TestData.players(jdbc, 1).get(0);
    for (int i = 1; i <= 5; i++) {
      submit(board.ref(), player, i * 100L, i % 2 == 0);
    }
    PlayerStats playerStats =
        stats.getPlayerStats(
            GetPlayerStatsRequest.newBuilder()
                .setGameId(board.gameId())
                .setPlayerId(player.toString())
                .build());
    assertThat(playerStats.getMatchesPlayed()).isEqualTo(5);
    assertThat(playerStats.getTotalScore()).isEqualTo(1_500);
    assertThat(playerStats.getBestScore()).isEqualTo(500);
    assertThat(playerStats.getWins()).isEqualTo(2);
    assertThat(playerStats.getWinRate()).isEqualTo(0.4);

    GetScoreHistoryRequest.Builder request =
        GetScoreHistoryRequest.newBuilder()
            .setGameId(board.gameId())
            .setPlayerId(player.toString())
            .setPageSize(2);
    GetScoreHistoryResponse page1 = stats.getScoreHistory(request.build());
    GetScoreHistoryResponse page2 =
        stats.getScoreHistory(request.setPageToken(page1.getNextPageToken()).build());
    GetScoreHistoryResponse page3 =
        stats.getScoreHistory(request.setPageToken(page2.getNextPageToken()).build());
    assertThat(page1.getEventsList()).extracting(e -> e.getScore()).containsExactly(500L, 400L);
    assertThat(page2.getEventsList()).extracting(e -> e.getScore()).containsExactly(300L, 200L);
    assertThat(page3.getEventsList()).extracting(e -> e.getScore()).containsExactly(100L);
    assertThat(page3.getNextPageToken()).isEmpty();
  }

  @Test
  void streamTopNPushesANewSnapshotWhenTheBoardChanges() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 2);
    submit(board.ref(), players.get(0), 100, false);

    List<GetTopNResponse> snapshots = new CopyOnWriteArrayList<>();
    var call = io.grpc.Context.current().withCancellation();
    call.run(
        () ->
            LeaderboardServiceGrpc.newStub(CHANNEL)
                .streamTopN(
                    StreamTopNRequest.newBuilder().setLeaderboard(board.ref()).setN(5).build(),
                    new StreamObserver<>() {
                      @Override
                      public void onNext(GetTopNResponse value) {
                        snapshots.add(value);
                      }

                      @Override
                      public void onError(Throwable t) {}

                      @Override
                      public void onCompleted() {}
                    }));
    await().atMost(Duration.ofSeconds(2)).until(() -> snapshots.size() == 1);

    submit(board.ref(), players.get(1), 500, false);
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                assertThat(snapshots.get(snapshots.size() - 1).getEntries(0).getPlayerId())
                    .isEqualTo(players.get(1).toString()));
    call.cancel(null);
  }

  @Test
  void invalidRequestsGetTheRightStatusCodes() {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    UUID player = TestData.players(jdbc, 1).get(0);

    assertStatus(() -> top(board.ref(), 5_000, 0), Status.Code.INVALID_ARGUMENT);
    assertStatus(
        () -> top(board.ref().toBuilder().setLeaderboardId("nope").build(), 10, 0),
        Status.Code.NOT_FOUND);
    assertStatus(
        () ->
            leaderboards.submitScore(
                SubmitScoreRequest.newBuilder()
                    .setLeaderboard(board.ref())
                    .setPlayerId("not-a-uuid")
                    .setScore(1)
                    .setIdempotencyKey("k")
                    .build()),
        Status.Code.INVALID_ARGUMENT);
    assertStatus(() -> submit(board.ref(), player, -1, false), Status.Code.INVALID_ARGUMENT);
    assertStatus(() -> submit(board.ref(), UUID.randomUUID(), 1, false), Status.Code.NOT_FOUND);
  }

  private static void assertStatus(Runnable call, Status.Code code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
  }
}
