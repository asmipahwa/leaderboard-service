package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.domain.Score;
import com.asmipahwa.leaderboard.domain.ScoreEvent;
import com.asmipahwa.leaderboard.domain.ScoreId;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.repository.PlayerStatsDao;
import com.asmipahwa.leaderboard.repository.ScoreEventRepository;
import com.asmipahwa.leaderboard.repository.ScoreRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Postgres half of a score submission, in one transaction: the event, the best score and the
 * player's stats either all commit or none do.
 *
 * <p>The best score uses optimistic locking: we read the row (with its version), decide in Java
 * whether the new score is better, and the UPDATE only succeeds if nobody changed the row in
 * between ({@code WHERE version = ?}). If someone did, the commit throws and the caller retries
 * with fresh data. Two brand-new rows for the same player collide on the primary key instead, which
 * is retried the same way.
 */
@Component
public class ScoreWriter {

  /**
   * @param improved whether this submission became the new best
   * @param bestScore best score after this submission
   */
  public record WriteOutcome(boolean improved, long bestScore) {}

  private final ScoreRepository scores;
  private final ScoreEventRepository events;
  private final PlayerStatsDao stats;
  private final EntityManager entityManager;

  public ScoreWriter(
      ScoreRepository scores,
      ScoreEventRepository events,
      PlayerStatsDao stats,
      EntityManager entityManager) {
    this.scores = scores;
    this.events = events;
    this.stats = stats;
    this.entityManager = entityManager;
  }

  @Transactional
  public WriteOutcome write(
      ResolvedBoard board,
      UUID playerId,
      long score,
      boolean win,
      String idempotencyKey,
      Instant now) {
    var key = board.key();
    ScoreId id = new ScoreId(key.gameId(), key.leaderboardId(), key.periodKey(), playerId);

    Score row = scores.findById(id).orElse(null);
    boolean improved;
    if (row == null) {
      row = new Score(id, score, now);
      entityManager.persist(row);
      improved = true;
    } else {
      // Changes the managed entity; Hibernate issues "UPDATE ... WHERE version = ?" on commit.
      improved = row.offer(score, board.sortOrder(), now);
    }

    events.save(
        new ScoreEvent(
            idempotencyKey, key.gameId(), key.leaderboardId(), playerId, score, improved, now));
    long points = board.sortOrder() == SortOrder.DESC ? score : 0;
    stats.recordMatch(key.gameId(), playerId, points, win, now);
    return new WriteOutcome(improved, row.getBestScore());
  }
}
