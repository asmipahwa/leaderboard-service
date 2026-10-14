package com.asmipahwa.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A player's best score on one leaderboard period. This row is the source of truth that the Redis
 * ranking cache is built from.
 *
 * <p>The {@link Version} column gives optimistic locking: if two requests read the same row and
 * both try to improve it, the second commit fails instead of silently overwriting the first.
 */
@Entity
@Table(name = "scores")
@IdClass(ScoreId.class)
public class Score {

  @Id
  @Column(name = "game_id")
  private String gameId;

  @Id
  @Column(name = "leaderboard_id")
  private String leaderboardId;

  @Id
  @Column(name = "period_key")
  private String periodKey;

  @Id
  @Column(name = "player_id")
  private UUID playerId;

  @Column(name = "best_score", nullable = false)
  private long bestScore;

  @Column(name = "achieved_at", nullable = false)
  private Instant achievedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected Score() {}

  public Score(ScoreId id, long bestScore, Instant achievedAt) {
    this.gameId = id.gameId();
    this.leaderboardId = id.leaderboardId();
    this.periodKey = id.periodKey();
    this.playerId = id.playerId();
    this.bestScore = bestScore;
    this.achievedAt = achievedAt;
  }

  /**
   * Keeps {@code candidate} if it beats the current best.
   *
   * @return true if the best score changed
   */
  public boolean offer(long candidate, SortOrder order, Instant at) {
    if (!order.isBetter(candidate, bestScore)) {
      return false;
    }
    this.bestScore = candidate;
    this.achievedAt = at;
    return true;
  }

  public ScoreId getId() {
    return new ScoreId(gameId, leaderboardId, periodKey, playerId);
  }

  public long getBestScore() {
    return bestScore;
  }

  public Instant getAchievedAt() {
    return achievedAt;
  }

  public long getVersion() {
    return version;
  }
}
