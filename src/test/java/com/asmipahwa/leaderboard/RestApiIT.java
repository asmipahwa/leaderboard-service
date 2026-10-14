package com.asmipahwa.leaderboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import com.asmipahwa.leaderboard.support.TestData;
import com.asmipahwa.leaderboard.support.TestData.Board;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The REST facade returns the same data as gRPC, with HTTP status codes for errors. */
class RestApiIT extends IntegrationTestSupport {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private void submit(String base, UUID player, long score) throws Exception {
    mvc.perform(
            post(base + "/scores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"playerId":"%s","score":%d,"idempotencyKey":"%s","win":true}
                    """
                        .formatted(player, score, UUID.randomUUID())))
        .andExpect(status().isOk());
  }

  @Test
  void submitThenReadTopRankFriendsStatsAndHistory() throws Exception {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    List<UUID> players = TestData.players(jdbc, 3);
    TestData.befriend(jdbc, players.get(0), players.get(1));
    String base = "/api/v1/games/" + board.gameId() + "/leaderboards/board";
    submit(base, players.get(0), 300);
    submit(base, players.get(1), 200);
    submit(base, players.get(2), 100);

    mvc.perform(get(base + "/top?n=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPlayers").value(3))
        .andExpect(jsonPath("$.entries[0].score").value(300))
        .andExpect(jsonPath("$.entries[1].rank").value(2));
    mvc.perform(get(base + "/players/" + players.get(1) + "/rank?around=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.me.rank").value(2))
        .andExpect(jsonPath("$.above[0].score").value(300))
        .andExpect(jsonPath("$.below[0].score").value(100));
    mvc.perform(get(base + "/players/" + players.get(0) + "/friends"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(2));

    String player = "/api/v1/games/" + board.gameId() + "/players/" + players.get(0);
    mvc.perform(get(player + "/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matchesPlayed").value(1))
        .andExpect(jsonPath("$.winRate").value(1.0));
    mvc.perform(get(player + "/history?pageSize=5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events[0].score").value(300));
  }

  @Test
  void errorsUseHttpStatusCodes() throws Exception {
    Board board = TestData.board(jdbc, SortOrder.DESC, ResetPeriod.NONE);
    String base = "/api/v1/games/" + board.gameId() + "/leaderboards/board";
    mvc.perform(get(base + "/top?n=5000")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/games/" + board.gameId() + "/leaderboards/missing/top"))
        .andExpect(status().isNotFound());
    mvc.perform(get(base + "/players/not-a-uuid/rank")).andExpect(status().isBadRequest());
    mvc.perform(
            post(base + "/scores")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playerId\":\"\",\"score\":-5}"))
        .andExpect(status().isBadRequest());
  }
}
