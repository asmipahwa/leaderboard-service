package com.asmipahwa.leaderboard.support;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates isolated test data. Every test gets its own game id, so tests never see each other's
 * boards even though they share one database.
 */
public final class TestData {

  /** A freshly created game + leaderboard. */
  public record Board(String gameId, String leaderboardId) {

    public LeaderboardRef ref() {
      return LeaderboardRef.newBuilder().setGameId(gameId).setLeaderboardId(leaderboardId).build();
    }
  }

  private TestData() {}

  public static Board board(JdbcTemplate jdbc, SortOrder order, ResetPeriod period) {
    String gameId = "it-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("INSERT INTO games (game_id, name) VALUES (?, ?)", gameId, "IT " + gameId);
    jdbc.update(
        "INSERT INTO leaderboards (game_id, leaderboard_id, name, sort_order, reset_period)"
            + " VALUES (?, 'board', 'Board', ?, ?)",
        gameId,
        order.name(),
        period.name());
    return new Board(gameId, "board");
  }

  /** Adds another board to an existing game. */
  public static Board addBoard(
      JdbcTemplate jdbc, String gameId, String leaderboardId, SortOrder order, ResetPeriod period) {
    jdbc.update(
        "INSERT INTO leaderboards (game_id, leaderboard_id, name, sort_order, reset_period)"
            + " VALUES (?, ?, ?, ?, ?)",
        gameId,
        leaderboardId,
        leaderboardId,
        order.name(),
        period.name());
    return new Board(gameId, leaderboardId);
  }

  public static List<UUID> players(JdbcTemplate jdbc, int count) {
    List<UUID> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO players (player_id, display_name, region) VALUES (?, ?, 'NA')",
          id,
          "Player" + i);
      ids.add(id);
    }
    return ids;
  }

  public static void befriend(JdbcTemplate jdbc, UUID one, UUID other) {
    boolean oneFirst = one.toString().compareTo(other.toString()) < 0;
    jdbc.update(
        "INSERT INTO friendships (player_a, player_b) VALUES (?, ?)",
        oneFirst ? one : other,
        oneFirst ? other : one);
  }
}
