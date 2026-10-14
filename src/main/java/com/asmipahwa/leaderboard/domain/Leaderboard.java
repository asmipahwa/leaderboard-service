package com.asmipahwa.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Definition of one ranking inside a game, such as {@code season-1-high-score}.
 *
 * <p>It only stores the rules (sort order and reset period). The actual standings live in {@code
 * scores} (truth) and a Redis sorted set (fast reads).
 */
@Entity
@Table(name = "leaderboards")
@IdClass(LeaderboardId.class)
public class Leaderboard {

  @Id
  @Column(name = "game_id")
  private String gameId;

  @Id
  @Column(name = "leaderboard_id")
  private String leaderboardId;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "sort_order", nullable = false)
  private SortOrder sortOrder;

  @Enumerated(EnumType.STRING)
  @Column(name = "reset_period", nullable = false)
  private ResetPeriod resetPeriod;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Leaderboard() {}

  public Leaderboard(
      String gameId,
      String leaderboardId,
      String name,
      SortOrder sortOrder,
      ResetPeriod resetPeriod) {
    this.gameId = gameId;
    this.leaderboardId = leaderboardId;
    this.name = name;
    this.sortOrder = sortOrder;
    this.resetPeriod = resetPeriod;
    this.createdAt = Instant.now();
  }

  public String getGameId() {
    return gameId;
  }

  public String getLeaderboardId() {
    return leaderboardId;
  }

  public String getName() {
    return name;
  }

  public SortOrder getSortOrder() {
    return sortOrder;
  }

  public ResetPeriod getResetPeriod() {
    return resetPeriod;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
