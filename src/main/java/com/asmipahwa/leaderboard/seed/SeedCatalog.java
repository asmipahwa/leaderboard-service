package com.asmipahwa.leaderboard.seed;

import com.asmipahwa.leaderboard.domain.ResetPeriod;
import com.asmipahwa.leaderboard.domain.SortOrder;
import java.util.List;
import java.util.Map;

/** The fixed set of demo games and leaderboards that {@link DataSeeder} creates. */
public final class SeedCatalog {

  /** A game to seed. */
  public record GameDef(String gameId, String name) {}

  /** A leaderboard to seed. */
  public record BoardDef(
      String gameId, String leaderboardId, String name, SortOrder sortOrder, ResetPeriod period) {}

  /** The board used by the load tests: every seeded player has a score on it. */
  public static final BoardDef BENCHMARK_BOARD =
      new BoardDef(
          "fc-mobile",
          "season-1-high-score",
          "Season 1 High Score",
          SortOrder.DESC,
          ResetPeriod.SEASON);

  public static final List<GameDef> GAMES =
      List.of(
          new GameDef("fc-mobile", "FC Mobile"),
          new GameDef("apex-lite", "Apex Lite"),
          new GameDef("nhl-arena", "NHL Arena"));

  public static final List<BoardDef> BOARDS =
      List.of(
          BENCHMARK_BOARD,
          new BoardDef(
              "fc-mobile",
              "all-time-high-score",
              "All-Time High Score",
              SortOrder.DESC,
              ResetPeriod.NONE),
          new BoardDef(
              "fc-mobile",
              "weekly-high-score",
              "Weekly High Score",
              SortOrder.DESC,
              ResetPeriod.WEEKLY),
          new BoardDef(
              "apex-lite",
              "season-1-high-score",
              "Season 1 High Score",
              SortOrder.DESC,
              ResetPeriod.SEASON),
          new BoardDef(
              "apex-lite",
              "daily-high-score",
              "Daily High Score",
              SortOrder.DESC,
              ResetPeriod.DAILY),
          new BoardDef(
              "apex-lite",
              "all-time-high-score",
              "All-Time High Score",
              SortOrder.DESC,
              ResetPeriod.NONE),
          new BoardDef(
              "nhl-arena",
              "all-time-high-score",
              "All-Time High Score",
              SortOrder.DESC,
              ResetPeriod.NONE),
          new BoardDef(
              "nhl-arena",
              "weekly-fastest-goal",
              "Weekly Fastest Goal (ms)",
              SortOrder.ASC,
              ResetPeriod.WEEKLY));

  /** Achievements created for every game: name to points needed in a single match. */
  public static final Map<String, Long> ACHIEVEMENTS =
      Map.of("Rising Star", 10_000L, "Pro", 20_000L, "Legend", 40_000L);

  private SeedCatalog() {}
}
