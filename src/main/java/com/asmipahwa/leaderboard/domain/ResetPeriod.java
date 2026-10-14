package com.asmipahwa.leaderboard.domain;

/**
 * How often a leaderboard starts over.
 *
 * <p>Games run daily challenges, weekly events and seasons. Instead of deleting rows when a period
 * ends, each period gets its own "period key" (see {@code PeriodKeyResolver}) so old periods stay
 * queryable and new ones start empty.
 */
public enum ResetPeriod {
  NONE,
  DAILY,
  WEEKLY,
  SEASON
}
