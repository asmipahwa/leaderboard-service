package com.asmipahwa.leaderboard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Updates a player's running stats with a single atomic upsert.
 *
 * <p>Counters such as "matches played + 1" don't need the read-compare-write logic that best scores
 * do: Postgres can apply {@code x = x + 1} atomically under its row lock, so concurrent submissions
 * never lose an increment and never conflict. That is why stats use an upsert and best scores use
 * optimistic locking.
 */
@Repository
public class PlayerStatsDao {

  private final JdbcTemplate jdbc;

  public PlayerStatsDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Counts one match.
   *
   * @param points score added to total/best; 0 for time-trial boards where a score isn't points
   */
  public void recordMatch(String gameId, UUID playerId, long points, boolean win, Instant at) {
    jdbc.update(
        "INSERT INTO player_stats (game_id, player_id, matches_played, total_score, best_score,"
            + " wins, last_played_at, version) VALUES (?, ?, 1, ?, ?, ?, ?, 0)"
            + " ON CONFLICT (game_id, player_id) DO UPDATE SET"
            + " matches_played = player_stats.matches_played + 1,"
            + " total_score = player_stats.total_score + EXCLUDED.total_score,"
            + " best_score = GREATEST(player_stats.best_score, EXCLUDED.best_score),"
            + " wins = player_stats.wins + EXCLUDED.wins,"
            + " last_played_at = GREATEST(player_stats.last_played_at, EXCLUDED.last_played_at),"
            + " version = player_stats.version + 1",
        gameId,
        playerId,
        points,
        points,
        win ? 1 : 0,
        Timestamp.from(at));
  }
}
