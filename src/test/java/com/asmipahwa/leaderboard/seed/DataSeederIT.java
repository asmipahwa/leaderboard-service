package com.asmipahwa.leaderboard.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Runs the seeder at small scale and checks the generated data is internally consistent. */
@ActiveProfiles("seed")
@TestPropertySource(properties = {"seed.players=500", "seed.events=3000", "seed.reset=true"})
class DataSeederIT extends IntegrationTestSupport {

  @Autowired JdbcTemplate jdbc;

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  @Test
  void seedsCatalogPlayersAndEvents() {
    assertThat(count("SELECT count(*) FROM games")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM leaderboards")).isEqualTo(8);
    assertThat(count("SELECT count(*) FROM players")).isEqualTo(500);
    assertThat(count("SELECT count(*) FROM score_events")).isEqualTo(3000);
    assertThat(count("SELECT count(*) FROM friendships")).isGreaterThan(500);
  }

  @Test
  void everyPlayerIsOnTheBenchmarkBoard() {
    assertThat(
            count(
                "SELECT count(*) FROM scores WHERE game_id = 'fc-mobile'"
                    + " AND leaderboard_id = 'season-1-high-score' AND period_key = 'S1'"))
        .isEqualTo(500);
  }

  @Test
  void bestScoresMatchTheEventHistory() {
    // For DESC boards, each best score must equal the max event score for that player and board.
    long mismatches =
        count(
            "SELECT count(*) FROM scores s JOIN leaderboards l USING (game_id, leaderboard_id)"
                + " WHERE l.reset_period = 'NONE' AND l.sort_order = 'DESC' AND s.best_score <> ("
                + "  SELECT max(e.score) FROM score_events e WHERE e.game_id = s.game_id"
                + "  AND e.leaderboard_id = s.leaderboard_id AND e.player_id = s.player_id)");
    assertThat(mismatches).isZero();
  }

  @Test
  void statsMatchTheEventHistory() {
    long mismatches =
        count(
            "SELECT count(*) FROM player_stats ps WHERE ps.matches_played <> ("
                + " SELECT count(*) FROM score_events e"
                + " WHERE e.game_id = ps.game_id AND e.player_id = ps.player_id)");
    assertThat(mismatches).isZero();
    assertThat(count("SELECT sum(matches_played) FROM player_stats")).isEqualTo(3000);
  }
}
