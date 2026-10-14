package com.asmipahwa.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.asmipahwa.leaderboard.domain.Game;
import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.domain.Player;
import com.asmipahwa.leaderboard.domain.Region;
import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.Score;
import com.asmipahwa.leaderboard.domain.ScoreId;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.repository.GameRepository;
import com.asmipahwa.leaderboard.repository.LeaderboardRepository;
import com.asmipahwa.leaderboard.repository.PlayerRepository;
import com.asmipahwa.leaderboard.repository.ScoreRepository;
import com.asmipahwa.leaderboard.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Proves that Flyway migrations apply cleanly, Hibernate's {@code ddl-auto=validate} accepts the
 * entity mappings, and the {@code @Version} column really rejects stale writes.
 */
class SchemaIT extends IntegrationTestSupport {

  @Autowired GameRepository games;
  @Autowired LeaderboardRepository leaderboards;
  @Autowired PlayerRepository players;
  @Autowired ScoreRepository scores;

  @Test
  void staleScoreUpdateIsRejectedByVersionColumn() {
    games.save(new Game("schema-it", "Schema IT"));
    leaderboards.save(
        new Leaderboard("schema-it", "high", "High", SortOrder.DESC, ResetPeriod.NONE));
    Player player = players.save(new Player(UUID.randomUUID(), "Tester", Region.NA));
    ScoreId id = new ScoreId("schema-it", "high", "all", player.getPlayerId());
    scores.save(new Score(id, 100, Instant.now()));

    Score first = scores.findById(id).orElseThrow();
    Score stale = scores.findById(id).orElseThrow();

    first.offer(200, SortOrder.DESC, Instant.now());
    scores.save(first);
    assertThat(scores.findById(id).orElseThrow().getVersion()).isEqualTo(1);

    stale.offer(150, SortOrder.DESC, Instant.now());
    assertThatThrownBy(() -> scores.save(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(scores.findById(id).orElseThrow().getBestScore()).isEqualTo(200);
  }
}
