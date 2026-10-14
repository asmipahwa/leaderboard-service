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
 * Running totals for one player in one game ("you've played 42 matches, won 18").
 *
 * <p>Kept as a pre-aggregated row so a profile screen is one primary-key lookup instead of a scan
 * over the player's whole event history.
 */
@Entity
@Table(name = "player_stats")
@IdClass(PlayerStatsId.class)
public class PlayerStatsEntity {

  @Id
  @Column(name = "game_id")
  private String gameId;

  @Id
  @Column(name = "player_id")
  private UUID playerId;

  @Column(name = "matches_played", nullable = false)
  private long matchesPlayed;

  @Column(name = "total_score", nullable = false)
  private long totalScore;

  @Column(name = "best_score", nullable = false)
  private long bestScore;

  @Column(nullable = false)
  private long wins;

  @Column(name = "last_played_at")
  private Instant lastPlayedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected PlayerStatsEntity() {}

  public PlayerStatsEntity(String gameId, UUID playerId) {
    this.gameId = gameId;
    this.playerId = playerId;
  }

  public String getGameId() {
    return gameId;
  }

  public UUID getPlayerId() {
    return playerId;
  }

  public long getMatchesPlayed() {
    return matchesPlayed;
  }

  public long getTotalScore() {
    return totalScore;
  }

  public long getBestScore() {
    return bestScore;
  }

  public long getWins() {
    return wins;
  }

  public Instant getLastPlayedAt() {
    return lastPlayedAt;
  }

  public long getVersion() {
    return version;
  }
}
