package com.asmipahwa.leaderboard.domain;

/**
 * Which direction counts as "better" on a leaderboard.
 *
 * <p>Most boards are {@link #DESC} (higher score wins), but time-trial boards such as a fastest lap
 * are {@link #ASC} (lower is better). Keeping it explicit means the write path and the Redis cache
 * never have to guess.
 */
public enum SortOrder {
  DESC,
  ASC;

  /** Returns true if {@code candidate} beats {@code current} under this ordering. */
  public boolean isBetter(long candidate, long current) {
    return this == DESC ? candidate > current : candidate < current;
  }

  /**
   * Converts a score to the value stored in the Redis sorted set. ASC boards store the negated
   * score so that, for every board, a higher stored value means a better result. That lets all
   * boards share the same commands ({@code ZREVRANGE}, {@code ZADD GT}).
   */
  public double toRankingScore(long score) {
    return this == DESC ? score : -score;
  }

  /** Inverse of {@link #toRankingScore(long)}. */
  public long fromRankingScore(double rankingScore) {
    long value = Math.round(rankingScore);
    return this == DESC ? value : -value;
  }
}
