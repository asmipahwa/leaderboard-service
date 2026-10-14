package com.asmipahwa.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.config.LeaderboardProperties;
import com.asmipahwa.leaderboard.domain.ResetPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PeriodKeyResolverTest {

  private static PeriodKeyResolver resolverAt(String instant) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return new PeriodKeyResolver(clock, new LeaderboardProperties("S1"));
  }

  @Test
  void allTimeBoardsAlwaysUseTheSameKey() {
    assertThat(resolverAt("2026-10-14T12:00:00Z").current(ResetPeriod.NONE)).isEqualTo("all");
  }

  @Test
  void seasonBoardsUseTheConfiguredSeason() {
    assertThat(resolverAt("2026-10-14T12:00:00Z").current(ResetPeriod.SEASON)).isEqualTo("S1");
  }

  @ParameterizedTest
  @CsvSource({
    "2026-10-14T00:00:00Z, 2026-10-14",
    "2026-10-14T23:59:59.999Z, 2026-10-14",
    "2026-10-15T00:00:00Z, 2026-10-15",
  })
  void dailyKeysRollOverAtUtcMidnight(String instant, String expected) {
    assertThat(resolverAt(instant).current(ResetPeriod.DAILY)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "2026-10-14T12:00:00Z, 2026-W42",
    // ISO weeks start on Monday: Sunday night is still the old week, Monday 00:00 is the new one.
    "2026-10-18T23:59:59Z, 2026-W42",
    "2026-10-19T00:00:00Z, 2026-W43",
    // Dec 31 2024 is a Tuesday, so it belongs to week 1 of 2025, not week 53 of 2024.
    "2024-12-31T10:00:00Z, 2025-W01",
    // Jan 1 2027 is a Friday, so it still belongs to the last week of 2026.
    "2027-01-01T10:00:00Z, 2026-W53",
  })
  void weeklyKeysFollowIsoWeeksInUtc(String instant, String expected) {
    assertThat(resolverAt(instant).current(ResetPeriod.WEEKLY)).isEqualTo(expected);
  }

  @Test
  void keysAreComputedInUtcNotLocalTime() {
    // 23:30 in Vancouver on Oct 14 is already Oct 15 in UTC.
    Instant vancouverLateEvening = Instant.parse("2026-10-15T06:30:00Z");
    assertThat(resolverAt("2026-01-01T00:00:00Z").at(ResetPeriod.DAILY, vancouverLateEvening))
        .isEqualTo("2026-10-15");
  }
}
