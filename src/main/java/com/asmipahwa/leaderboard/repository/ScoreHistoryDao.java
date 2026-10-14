package com.asmipahwa.leaderboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads a player's match history newest-first with keyset pagination, so page 50 is as cheap as
 * page 1 (it uses {@code idx_events_player} instead of skipping rows with OFFSET).
 */
@Repository
public class ScoreHistoryDao {

  /** One history row. */
  public record EventRow(
      long eventId, String leaderboardId, long score, boolean accepted, Instant receivedAt) {}

  private final JdbcTemplate jdbc;

  public ScoreHistoryDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param beforeTime cursor time, or null for the first page
   * @param beforeId cursor event id (tie-breaker for equal timestamps)
   */
  public List<EventRow> page(
      String gameId, UUID playerId, Instant beforeTime, long beforeId, int limit) {
    String sql =
        "SELECT event_id, leaderboard_id, score, accepted, received_at FROM score_events"
            + " WHERE player_id = ? AND game_id = ?"
            + (beforeTime == null ? "" : " AND (received_at, event_id) < (?, ?)")
            + " ORDER BY received_at DESC, event_id DESC LIMIT ?";
    Object[] args =
        beforeTime == null
            ? new Object[] {playerId, gameId, limit}
            : new Object[] {playerId, gameId, Timestamp.from(beforeTime), beforeId, limit};
    return jdbc.query(
        sql,
        (rs, i) ->
            new EventRow(
                rs.getLong("event_id"),
                rs.getString("leaderboard_id"),
                rs.getLong("score"),
                rs.getBoolean("accepted"),
                rs.getTimestamp("received_at").toInstant()),
        args);
  }
}
