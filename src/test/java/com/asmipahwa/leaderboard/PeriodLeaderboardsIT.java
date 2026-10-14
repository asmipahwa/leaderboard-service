package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardRef;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.service.PeriodKeyResolver;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.MutableClock;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Weekly boards reset by switching period keys, while all-time boards keep everything. */
class PeriodLeaderboardsIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired PeriodKeyResolver periods;

  private final LeaderboardServiceGrpc.LeaderboardServiceBlockingStub stub =
      LeaderboardServiceGrpc.newBlockingStub(CHANNEL);

  @AfterEach
  void resetClock() {
    clock.reset();
  }

  private void submit(LeaderboardRef ref, UUID player, long score) {
    stub.submitScore(
        SubmitScoreRequest.newBuilder()
            .setLeaderboard(ref)
            .setPlayerId(player.toString())
            .setScore(score)
            .setIdempotencyKey(UUID.randomUUID().toString())
            .build());
  }

  private GetTopNResponse top(LeaderboardRef ref) {
    return stub.getTopN(GetTopNRequest.newBuilder().setLeaderboard(ref).setN(10).build());
  }

  @Test
  void weeklyBoardStartsEmptyNextWeekButOldWeekStaysReadable() {
    Board allTime = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    Board weekly =
        TestData.addBoard(jdbc, allTime.gameId(), "weekly", SortOrder.DESC, ResetPeriod.WEEKLY);
    UUID player = TestData.players(jdbc, 1).get(0);
    String thisWeek = periods.current(ResetPeriod.WEEKLY);

    submit(allTime.ref(), player, 700);
    submit(weekly.ref(), player, 700);
    assertThat(top(allTime.ref()).getEntriesCount()).isEqualTo(1);
    assertThat(top(weekly.ref()).getEntriesCount()).isEqualTo(1);

    clock.advance(Duration.ofDays(7));
    assertThat(periods.current(ResetPeriod.WEEKLY)).isNotEqualTo(thisWeek);

    // New week: the weekly board is empty, the all-time board is unchanged.
    assertThat(top(weekly.ref()).getEntriesCount()).isZero();
    assertThat(top(allTime.ref()).getEntries(0).getScore()).isEqualTo(700);

    // Last week is still there when asked for explicitly.
    GetTopNResponse lastWeek = top(weekly.ref().toBuilder().setPeriodKey(thisWeek).build());
    assertThat(lastWeek.getEntries(0).getScore()).isEqualTo(700);

    // A lower score this week still counts, because the week starts from zero.
    submit(weekly.ref(), player, 300);
    assertThat(top(weekly.ref()).getEntries(0).getScore()).isEqualTo(300);
    assertThat(top(allTime.ref()).getEntries(0).getScore()).isEqualTo(700);
  }
}
