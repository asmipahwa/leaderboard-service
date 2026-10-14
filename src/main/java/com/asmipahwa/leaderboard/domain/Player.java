package com.asmipahwa.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A player account as this service sees it: an id plus the display name shown on leaderboards.
 * Identity and auth belong to another platform service; we only keep what ranking needs.
 */
@Entity
@Table(name = "players")
public class Player {

  @Id
  @Column(name = "player_id")
  private UUID playerId;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Region region;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Player() {}

  public Player(UUID playerId, String displayName, Region region) {
    this.playerId = playerId;
    this.displayName = displayName;
    this.region = region;
    this.createdAt = Instant.now();
  }

  public UUID getPlayerId() {
    return playerId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Region getRegion() {
    return region;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
