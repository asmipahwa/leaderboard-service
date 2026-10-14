package com.asmipahwa.leaderboard.service;

import java.time.Instant;
import java.util.List;

/**
 * A page of a leaderboard plus the total number of ranked players.
 *
 * @param entriesVersion increases whenever a score on the board improves (0 if unknown, e.g. when
 *     served from the Postgres fallback)
 */
public record TopNResult(
    List<RankedEntry> entries, long totalPlayers, Instant asOf, long entriesVersion) {

  public TopNResult(List<RankedEntry> entries, long totalPlayers, Instant asOf) {
    this(entries, totalPlayers, asOf, 0);
  }
}
