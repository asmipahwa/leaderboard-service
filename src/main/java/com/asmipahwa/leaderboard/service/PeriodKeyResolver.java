package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.config.LeaderboardProperties;
import com.asmipahwa.leaderboard.domain.ResetPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import org.springframework.stereotype.Component;

/**
 * Turns a reset period plus a moment in time into a period key such as {@code 2026-W42}.
 *
 * <p>Resets are done by switching keys, not by deleting data: on Monday the weekly board simply
 * starts writing to a new key. Everything uses UTC so a board resets at the same instant for every
 * player in every region. Weeks follow ISO-8601, so Dec 31 can belong to week 1 of the next year.
 */
@Component
public class PeriodKeyResolver {

  /** Key used by boards that never reset. */
  public static final String ALL_TIME = "all";

  private final Clock clock;
  private final String currentSeason;

  public PeriodKeyResolver(Clock clock, LeaderboardProperties properties) {
    this.clock = clock;
    this.currentSeason = properties.currentSeason();
  }

  /** The period key that new submissions should be written to right now. */
  public String current(ResetPeriod period) {
    return at(period, clock.instant());
  }

  /** The period key that was active at {@code instant}. */
  public String at(ResetPeriod period, Instant instant) {
    ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
    return switch (period) {
      case NONE -> ALL_TIME;
      case DAILY -> utc.toLocalDate().toString();
      case WEEKLY ->
          String.format(
              "%d-W%02d",
              utc.get(IsoFields.WEEK_BASED_YEAR), utc.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        // Seasons are scheduled by live-ops, not by the calendar, so the active one is configured.
      case SEASON -> currentSeason;
    };
  }
}
