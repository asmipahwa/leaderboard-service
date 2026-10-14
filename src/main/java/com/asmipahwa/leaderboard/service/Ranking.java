package com.asmipahwa.leaderboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a best-first list of scores into ranks using standard competition ranking ("1224"): tied
 * players share a rank and the next rank is skipped, like in sports results.
 *
 * <p>The rank of a player is 1 + the number of players with a strictly better score. Within a page
 * that means: same score as the previous entry gives the same rank, otherwise the rank is the
 * entry's 1-based position. Only the first entry of a page needs a lookup ({@code firstRank}),
 * because the entry before the page could be tied with it.
 */
public final class Ranking {

  /** A score already converted back from Redis, still without a rank. */
  public record Scored(String playerId, long score) {}

  private Ranking() {}

  /**
   * @param sorted entries ordered best first
   * @param firstPosition 0-based position of {@code sorted.get(0)} in the whole board
   * @param firstRank competition rank of {@code sorted.get(0)}
   * @param names player id to display name
   */
  public static List<RankedEntry> assign(
      List<Scored> sorted, long firstPosition, long firstRank, Map<String, String> names) {
    List<RankedEntry> ranked = new ArrayList<>(sorted.size());
    long previousRank = 0;
    long previousScore = 0;
    for (int i = 0; i < sorted.size(); i++) {
      Scored entry = sorted.get(i);
      long rank;
      if (i == 0) {
        rank = firstRank;
      } else if (entry.score() == previousScore) {
        rank = previousRank;
      } else {
        rank = firstPosition + i + 1;
      }
      ranked.add(
          new RankedEntry(
              rank, entry.playerId(), names.getOrDefault(entry.playerId(), ""), entry.score()));
      previousRank = rank;
      previousScore = entry.score();
    }
    return ranked;
  }

  /** Share of the board ranked below this player: rank 1 of 100 beats 99%, so 0.99. */
  public static double percentile(long rank, long totalPlayers) {
    if (totalPlayers <= 0) {
      return 0.0;
    }
    return 1.0 - (double) rank / totalPlayers;
  }
}
