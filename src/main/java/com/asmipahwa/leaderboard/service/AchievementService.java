package com.asmipahwa.leaderboard.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Unlocks threshold achievements ("score 10,000 points") as part of a score submission.
 *
 * <p>Rules are cached per game, so a submission below every threshold costs no database call.
 * Unlocking uses {@code INSERT ... ON CONFLICT DO NOTHING} on the (player, achievement) primary
 * key: the row count tells us whether this call was the one that unlocked it, so each achievement
 * is reported exactly once even when submissions race.
 */
@Service
public class AchievementService {

  /** One rule: reach {@code threshold} points in a single match. */
  public record Rule(long id, String name, long threshold) {}

  private final JdbcTemplate jdbc;
  private final Map<String, List<Rule>> rulesByGame = new ConcurrentHashMap<>();

  public AchievementService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Unlocks every rule this score qualifies for. Must run inside the submission transaction so a
   * rolled-back submission never leaves an unlocked achievement behind.
   *
   * @return names of achievements unlocked by this call (empty if none are new)
   */
  public List<String> unlock(String gameId, UUID playerId, long score, Instant at) {
    List<String> unlocked = new ArrayList<>();
    for (Rule rule : rulesFor(gameId)) {
      if (score < rule.threshold()) {
        break; // rules are sorted by threshold
      }
      int inserted =
          jdbc.update(
              "INSERT INTO player_achievements (player_id, achievement_id, unlocked_at)"
                  + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
              playerId,
              rule.id(),
              Timestamp.from(at));
      if (inserted == 1) {
        unlocked.add(rule.name());
      }
    }
    return unlocked;
  }

  /** Names of everything this player has unlocked in this game, easiest first. */
  public List<String> achievementsOf(String gameId, UUID playerId) {
    return jdbc.queryForList(
        "SELECT a.name FROM player_achievements pa"
            + " JOIN achievements a ON a.achievement_id = pa.achievement_id"
            + " WHERE a.game_id = ? AND pa.player_id = ? ORDER BY a.threshold",
        String.class,
        gameId,
        playerId);
  }

  List<Rule> rulesFor(String gameId) {
    return rulesByGame.computeIfAbsent(
        gameId,
        game ->
            jdbc.query(
                "SELECT achievement_id, name, threshold FROM achievements WHERE game_id = ?"
                    + " ORDER BY threshold",
                (rs, i) ->
                    new Rule(
                        rs.getLong("achievement_id"),
                        rs.getString("name"),
                        rs.getLong("threshold")),
                game));
  }
}
