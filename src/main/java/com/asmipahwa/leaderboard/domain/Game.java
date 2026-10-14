package com.asmipahwa.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A game title (for example {@code fc-mobile}). Every leaderboard and stat is scoped to one game,
 * which is how a shared platform service keeps titles isolated from each other.
 */
@Entity
@Table(name = "games")
public class Game {

  @Id
  @Column(name = "game_id")
  private String gameId;

  @Column(nullable = false)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Game() {}

  public Game(String gameId, String name) {
    this.gameId = gameId;
    this.name = name;
    this.createdAt = Instant.now();
  }

  public String getGameId() {
    return gameId;
  }

  public String getName() {
    return name;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
