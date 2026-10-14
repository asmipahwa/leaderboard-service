package com.asmipahwa.leaderboard.cache;

/**
 * Identifies one leaderboard period and knows its Redis key names.
 *
 * <p>Key layout:
 *
 * <ul>
 *   <li>{@code lb:{game}:{board}:{period}}: sorted set, member = player id, score = best score
 *   <li>{@code lbmeta:{game}:{board}:{period}}: {@code building} or {@code ready}; tells readers
 *       whether the sorted set is complete
 *   <li>{@code lock:rebuild:{game}:{board}:{period}}: short-lived lock so only one pod rebuilds
 *   <li>{@code lbver:{game}:{board}:{period}}: counter bumped whenever a score improves
 * </ul>
 */
public record BoardKey(String gameId, String leaderboardId, String periodKey) {

  private String suffix() {
    return gameId + ":" + leaderboardId + ":" + periodKey;
  }

  public String zsetKey() {
    return "lb:" + suffix();
  }

  public String metaKey() {
    return "lbmeta:" + suffix();
  }

  public String versionKey() {
    return "lbver:" + suffix();
  }

  public String lockKey() {
    return "lock:rebuild:" + suffix();
  }

  @Override
  public String toString() {
    return suffix();
  }
}
