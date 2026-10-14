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
}
