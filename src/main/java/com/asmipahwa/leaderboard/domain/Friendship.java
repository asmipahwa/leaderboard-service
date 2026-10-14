package com.asmipahwa.leaderboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * An undirected friendship between two players, used for friends leaderboards.
 *
 * <p>The pair is always stored with {@code playerA < playerB} (the table has a CHECK for it), so
 * each friendship exists exactly once no matter who added whom.
 */
@Entity
@Table(name = "friendships")
@IdClass(FriendshipId.class)
public class Friendship {

  @Id
  @Column(name = "player_a")
  private UUID playerA;

  @Id
  @Column(name = "player_b")
  private UUID playerB;

  protected Friendship() {}

  private Friendship(UUID playerA, UUID playerB) {
    this.playerA = playerA;
    this.playerB = playerB;
  }

  /**
   * Creates a friendship with the ids in canonical order. Postgres compares UUIDs byte by byte,
   * which matches comparing their lowercase hex strings (and not {@link UUID#compareTo}, which is
   * signed).
   */
  public static Friendship between(UUID one, UUID other) {
    if (one.equals(other)) {
      throw new IllegalArgumentException("A player cannot befriend themselves");
    }
    return one.toString().compareTo(other.toString()) < 0
        ? new Friendship(one, other)
        : new Friendship(other, one);
  }

  public UUID getPlayerA() {
    return playerA;
  }

  public UUID getPlayerB() {
    return playerB;
  }
}
