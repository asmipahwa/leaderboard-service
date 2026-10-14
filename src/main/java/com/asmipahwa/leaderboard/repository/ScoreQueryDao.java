package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.service.RankedEntry;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain-SQL reads of the {@code scores} table that JPA would make awkward or slow: streaming a
 * whole board to rebuild Redis, and ranking queries for the Postgres fallback path.
 */
@Repository
public class ScoreQueryDao {

  /** One row of a board as stored in Postgres. */
  public record ScoreRow(UUID playerId, String displayName, long bestScore) {}

  private final JdbcTemplate jdbc;

  public ScoreQueryDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Streams every row of a board period in pages, using keyset pagination on {@code player_id}
   * ({@code WHERE player_id > last}). Unlike OFFSET, each page costs the same no matter how deep
   * into the board we are.
   */
  public void forEachPage(
      String gameId,
      String leaderboardId,
      String periodKey,
      int pageSize,
      Consumer<List<ScoreRow>> consumer) {
    UUID after = new UUID(0, 0);
    String sql =
        "SELECT s.player_id, p.display_name, s.best_score FROM scores s"
            + " JOIN players p ON p.player_id = s.player_id"
            + " WHERE s.game_id = ? AND s.leaderboard_id = ? AND s.period_key = ?"
            + " AND s.player_id > ? ORDER BY s.player_id LIMIT ?";
    while (true) {
      List<ScoreRow> page =
          jdbc.query(
              sql,
              (rs, i) ->
                  new ScoreRow(
                      rs.getObject("player_id", UUID.class),
                      rs.getString("display_name"),
                      rs.getLong("best_score")),
              gameId,
              leaderboardId,
              periodKey,
              after,
              pageSize);
      if (page.isEmpty()) {
        return;
      }
      consumer.accept(page);
      if (page.size() < pageSize) {
        return;
      }
      after = page.get(page.size() - 1).playerId();
    }
  }

  /**
   * Top N straight from Postgres, used only when Redis is unavailable. Slower (it ranks the whole
   * board with a window function) but keeps the leaderboard up. Ties are ordered by player id
   * descending, the same order Redis uses, so both paths return identical pages.
   */
  public List<RankedEntry> topN(
      String gameId,
      String leaderboardId,
      String periodKey,
      SortOrder order,
      int offset,
      int limit) {
    String direction = order == SortOrder.DESC ? "DESC" : "ASC";
    String sql =
        "SELECT rank() OVER (ORDER BY s.best_score "
            + direction
            + ") AS rnk, s.player_id, p.display_name, s.best_score FROM scores s"
            + " JOIN players p ON p.player_id = s.player_id"
            + " WHERE s.game_id = ? AND s.leaderboard_id = ? AND s.period_key = ?"
            + " ORDER BY s.best_score "
            + direction
            + ", s.player_id::text DESC OFFSET ? LIMIT ?";
    return jdbc.query(
        sql,
        (rs, i) ->
            new RankedEntry(
                rs.getLong("rnk"),
                rs.getString("player_id"),
                rs.getString("display_name"),
                rs.getLong("best_score")),
        gameId,
        leaderboardId,
        periodKey,
        offset,
        limit);
  }

  /** A player's best score on a board period, if they have one. */
  public java.util.Optional<Long> bestScore(
      String gameId, String leaderboardId, String periodKey, UUID playerId) {
    List<Long> rows =
        jdbc.queryForList(
            "SELECT best_score FROM scores WHERE game_id = ? AND leaderboard_id = ?"
                + " AND period_key = ? AND player_id = ?",
            Long.class,
            gameId,
            leaderboardId,
            periodKey,
            playerId);
    return rows.stream().findFirst();
  }

  /** Number of players with a strictly better score: the competition rank is this + 1. */
  public long countBetter(
      String gameId, String leaderboardId, String periodKey, SortOrder order, long score) {
    String comparison = order == SortOrder.DESC ? ">" : "<";
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM scores WHERE game_id = ? AND leaderboard_id = ?"
                + " AND period_key = ? AND best_score "
                + comparison
                + " ?",
            Long.class,
            gameId,
            leaderboardId,
            periodKey,
            score);
    return count == null ? 0 : count;
  }

  /**
   * 0-based position of a player in the board's display order (best first, ties by player id
   * descending): everyone better, plus tied players with a larger id.
   */
  public long position(
      String gameId,
      String leaderboardId,
      String periodKey,
      SortOrder order,
      UUID playerId,
      long score) {
    Long tiedAhead =
        jdbc.queryForObject(
            "SELECT count(*) FROM scores WHERE game_id = ? AND leaderboard_id = ?"
                + " AND period_key = ? AND best_score = ? AND player_id > ?",
            Long.class,
            gameId,
            leaderboardId,
            periodKey,
            score,
            playerId);
    return countBetter(gameId, leaderboardId, periodKey, order, score)
        + (tiedAhead == null ? 0 : tiedAhead);
  }

  /** Best scores of specific players (used by the friends board fallback). */
  public List<ScoreRow> scoresOf(
      String gameId, String leaderboardId, String periodKey, List<UUID> playerIds) {
    if (playerIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT s.player_id, p.display_name, s.best_score FROM scores s"
            + " JOIN players p ON p.player_id = s.player_id"
            + " WHERE s.game_id = ? AND s.leaderboard_id = ? AND s.period_key = ?"
            + " AND s.player_id = ANY (?)",
        (rs, i) ->
            new ScoreRow(
                rs.getObject("player_id", UUID.class),
                rs.getString("display_name"),
                rs.getLong("best_score")),
        gameId,
        leaderboardId,
        periodKey,
        playerIds.toArray(UUID[]::new));
  }

  /**
   * A cheap pseudo-random sample for the reconciler: up to {@code limit} rows starting at a random
   * player id. It walks the primary-key index instead of scanning the board like {@code ORDER BY
   * random()} would.
   */
  public List<ScoreRow> sample(
      String gameId, String leaderboardId, String periodKey, UUID from, int limit) {
    return jdbc.query(
        "SELECT s.player_id, p.display_name, s.best_score FROM scores s"
            + " JOIN players p ON p.player_id = s.player_id"
            + " WHERE s.game_id = ? AND s.leaderboard_id = ? AND s.period_key = ?"
            + " AND s.player_id >= ? ORDER BY s.player_id LIMIT ?",
        (rs, i) ->
            new ScoreRow(
                rs.getObject("player_id", UUID.class),
                rs.getString("display_name"),
                rs.getLong("best_score")),
        gameId,
        leaderboardId,
        periodKey,
        from,
        limit);
  }

  public long count(String gameId, String leaderboardId, String periodKey) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM scores WHERE game_id = ? AND leaderboard_id = ?"
                + " AND period_key = ?",
            Long.class,
            gameId,
            leaderboardId,
            periodKey);
    return count == null ? 0 : count;
  }
}
