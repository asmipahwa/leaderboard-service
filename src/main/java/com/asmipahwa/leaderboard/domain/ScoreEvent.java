package com.asmipahwa.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of every score submission, accepted or not.
 *
 * <p>It gives players a match history, and its unique {@code idempotency_key} is the final guard
 * against a retried request being counted twice.
 */
@Entity
@Table(name = "score_events")
public class ScoreEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "event_id")
  private Long eventId;

  @Column(name = "idempotency_key", nullable = false, unique = true)
  private String idempotencyKey;

  @Column(name = "game_id", nullable = false)
  private String gameId;

  @Column(name = "leaderboard_id", nullable = false)
  private String leaderboardId;

  @Column(name = "player_id", nullable = false)
  private UUID playerId;

  @Column(nullable = false)
  private long score;

  @Column(nullable = false)
  private boolean accepted;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected ScoreEvent() {}

  public ScoreEvent(
      String idempotencyKey,
      String gameId,
      String leaderboardId,
      UUID playerId,
      long score,
      boolean accepted,
      Instant receivedAt) {
    this.idempotencyKey = idempotencyKey;
    this.gameId = gameId;
    this.leaderboardId = leaderboardId;
    this.playerId = playerId;
    this.score = score;
    this.accepted = accepted;
    this.receivedAt = receivedAt;
  }

  public Long getEventId() {
    return eventId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getGameId() {
    return gameId;
  }

  public String getLeaderboardId() {
    return leaderboardId;
  }

  public UUID getPlayerId() {
    return playerId;
  }

  public long getScore() {
    return score;
  }

  public boolean isAccepted() {
    return accepted;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
