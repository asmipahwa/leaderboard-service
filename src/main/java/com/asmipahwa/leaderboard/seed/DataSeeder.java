package com.asmipahwa.leaderboard.seed;

import com.asmipahwa.leaderboard.domain.Region;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.seed.SeedCatalog.BoardDef;
import com.asmipahwa.leaderboard.seed.SeedCatalog.GameDef;
import com.asmipahwa.leaderboard.service.PeriodKeyResolver;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fills Postgres with realistic, repeatable demo data: 3 games, 8 leaderboards, players,
 * friendships, a week of score events, and the best scores and stats those events imply.
 *
 * <p>It only runs with the {@code seed} profile. Events are generated in time order and the best
 * scores and stats are derived from them, so the seeded data is self-consistent (exactly what the
 * live write path would have produced). Every seeded player gets a score on the benchmark board so
 * load tests read from a board with the full player count. Redis is not touched: the service
 * rebuilds its ranking cache from these tables.
 */
@Component
@Profile("seed")
public class DataSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
  private static final String[] ADJECTIVES = {
    "Swift", "Silent", "Golden", "Rapid", "Iron", "Shadow", "Lucky", "Frozen", "Crimson", "Neon",
    "Clutch", "Wild", "Cosmic", "Turbo", "Brave", "Arctic"
  };
  private static final String[] NOUNS = {
    "Falcon",
    "Striker",
    "Viper",
    "Goalie",
    "Phantom",
    "Rocket",
    "Tiger",
    "Wolf",
    "Comet",
    "Knight",
    "Ranger",
    "Panda",
    "Blaze",
    "Ninja",
    "Otter",
    "Maverick"
  };
  private static final Duration HISTORY = Duration.ofDays(7);
  private static final double WIN_RATE = 0.45;

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final SeedProperties props;
  private final PeriodKeyResolver periods;
  private final Clock clock;

  public DataSeeder(
      DataSource dataSource, SeedProperties props, PeriodKeyResolver periods, Clock clock) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.props = props;
    this.periods = periods;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    Integer existingGames = jdbc.queryForObject("SELECT count(*) FROM games", Integer.class);
    if (existingGames != null && existingGames > 0) {
      if (!props.reset()) {
        log.info(
            "Seed skipped: database already has {} games (set seed.reset=true)", existingGames);
        return;
      }
      jdbc.execute(
          "TRUNCATE player_stats, score_events, scores, friendships, players, leaderboards, games"
              + " CASCADE");
    }
    long started = System.nanoTime();
    seed();
    log.info("Seed finished in {} ms", (System.nanoTime() - started) / 1_000_000);
  }

  private void seed() throws Exception {
    Random random = new Random(props.randomSeed());
    seedCatalog();
    UUID[] playerIds = newPlayerIds(random);

    try (Connection connection = dataSource.getConnection()) {
      seedPlayers(connection, playerIds, random);
      seedFriendships(connection, playerIds, random);
      seedEventsScoresAndStats(connection, playerIds, random);
    }
    jdbc.execute("ANALYZE");
  }

  private void seedCatalog() {
    Instant now = clock.instant();
    for (GameDef game : SeedCatalog.GAMES) {
      jdbc.update(
          "INSERT INTO games (game_id, name, created_at) VALUES (?, ?, ?)",
          game.gameId(),
          game.name(),
          java.sql.Timestamp.from(now));
    }
    for (BoardDef board : SeedCatalog.BOARDS) {
      jdbc.update(
          "INSERT INTO leaderboards (game_id, leaderboard_id, name, sort_order, reset_period,"
              + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
          board.gameId(),
          board.leaderboardId(),
          board.name(),
          board.sortOrder().name(),
          board.period().name(),
          java.sql.Timestamp.from(now));
    }
    log.info(
        "Seeded {} games, {} leaderboards", SeedCatalog.GAMES.size(), SeedCatalog.BOARDS.size());
  }

  private UUID[] newPlayerIds(Random random) {
    UUID[] ids = new UUID[props.players()];
    for (int i = 0; i < ids.length; i++) {
      // Random version-4 UUIDs, but drawn from the seeded Random so runs are repeatable.
      long msb = (random.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L;
      long lsb = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
      ids[i] = new UUID(msb, lsb);
    }
    return ids;
  }

  private void seedPlayers(Connection connection, UUID[] playerIds, Random random)
      throws Exception {
    Region[] regions = Region.values();
    String createdAt = clock.instant().minus(HISTORY.multipliedBy(4)).toString();
    try (CopyWriter copy =
        new CopyWriter(connection, "players", "player_id, display_name, region, created_at")) {
      for (UUID id : playerIds) {
        String name =
            ADJECTIVES[random.nextInt(ADJECTIVES.length)]
                + NOUNS[random.nextInt(NOUNS.length)]
                + random.nextInt(10_000);
        copy.row(id, name, regions[random.nextInt(regions.length)], createdAt);
      }
      log.info("Seeded {} players", copy.rows());
    }
  }

  private void seedFriendships(Connection connection, UUID[] playerIds, Random random)
      throws Exception {
    int n = playerIds.length;
    if (n < 2) {
      return;
    }
    // Each player "adds" avgFriends/2 others; since friendship is undirected that averages out
    // to roughly avgFriends friends per player.
    int addsPerPlayer = Math.max(1, props.avgFriends() / 2);
    Set<Long> pairs = new HashSet<>();
    try (CopyWriter copy = new CopyWriter(connection, "friendships", "player_a, player_b")) {
      for (int i = 0; i < n; i++) {
        for (int k = 0; k < addsPerPlayer; k++) {
          int j = random.nextInt(n);
          if (j == i) {
            continue;
          }
          UUID a = playerIds[i];
          UUID b = playerIds[j];
          boolean aFirst = a.toString().compareTo(b.toString()) < 0;
          int lo = aFirst ? i : j;
          int hi = aFirst ? j : i;
          if (pairs.add((long) lo * n + hi)) {
            copy.row(playerIds[lo], playerIds[hi]);
          }
        }
      }
      log.info("Seeded {} friendships", copy.rows());
    }
  }

  /** Key of one best-score row. */
  private record ScoreKey(int board, String periodKey, int player) {}

  /** Mutable best score while replaying events. */
  private static final class Best {
    long score;
    Instant at;

    Best(long score, Instant at) {
      this.score = score;
      this.at = at;
    }
  }

  /** Mutable per-game stats while replaying events. */
  private static final class Stats {
    long matches;
    long total;
    long best;
    long wins;
    Instant lastPlayed;
  }

  private void seedEventsScoresAndStats(Connection connection, UUID[] playerIds, Random random)
      throws Exception {
    List<BoardDef> boards = SeedCatalog.BOARDS;
    int benchmark = boards.indexOf(SeedCatalog.BENCHMARK_BOARD);
    int players = playerIds.length;
    int events = Math.max(props.events(), players);
    Instant start = clock.instant().minus(HISTORY);
    long stepNanos = HISTORY.toNanos() / events;

    Map<ScoreKey, Best> bestScores = new HashMap<>();
    Map<String, Stats> stats = new HashMap<>();
    int nextBenchmarkPlayer = 0;

    try (CopyWriter copy =
        new CopyWriter(
            connection,
            "score_events",
            "idempotency_key, game_id, leaderboard_id, player_id, score, accepted, received_at")) {
      for (int i = 0; i < events; i++) {
        Instant at = start.plusNanos(stepNanos * i);
        // Spread one benchmark-board event per player evenly through the history so every
        // player ends up on that board; everything else goes to a random board and player.
        boolean benchmarkTurn =
            nextBenchmarkPlayer < players
                && (long) i * players >= (long) nextBenchmarkPlayer * events;
        int boardIndex = benchmarkTurn ? benchmark : random.nextInt(boards.size());
        int player = benchmarkTurn ? nextBenchmarkPlayer++ : random.nextInt(players);
        BoardDef board = boards.get(boardIndex);
        long score = randomScore(board.sortOrder(), random);

        ScoreKey key = new ScoreKey(boardIndex, periods.at(board.period(), at), player);
        Best best = bestScores.get(key);
        boolean accepted = best == null || board.sortOrder().isBetter(score, best.score);
        if (best == null) {
          bestScores.put(key, new Best(score, at));
        } else if (accepted) {
          best.score = score;
          best.at = at;
        }

        Stats s = stats.computeIfAbsent(board.gameId() + "|" + player, k -> new Stats());
        s.matches++;
        // Stats track points. Time-trial (ASC) results count as a match but are not points.
        if (board.sortOrder() == SortOrder.DESC) {
          s.total += score;
          s.best = Math.max(s.best, score);
        }
        if (random.nextDouble() < WIN_RATE) {
          s.wins++;
        }
        s.lastPlayed = at;

        copy.row(
            "seed-" + i,
            board.gameId(),
            board.leaderboardId(),
            playerIds[player],
            score,
            accepted,
            at);
      }
      log.info("Seeded {} score events", copy.rows());
    }

    try (CopyWriter copy =
        new CopyWriter(
            connection,
            "scores",
            "game_id, leaderboard_id, period_key, player_id, best_score, achieved_at, version")) {
      for (Map.Entry<ScoreKey, Best> e : bestScores.entrySet()) {
        BoardDef board = boards.get(e.getKey().board());
        copy.row(
            board.gameId(),
            board.leaderboardId(),
            e.getKey().periodKey(),
            playerIds[e.getKey().player()],
            e.getValue().score,
            e.getValue().at,
            0);
      }
      log.info("Seeded {} best-score rows", copy.rows());
    }

    try (CopyWriter copy =
        new CopyWriter(
            connection,
            "player_stats",
            "game_id, player_id, matches_played, total_score, best_score, wins, last_played_at,"
                + " version")) {
      for (Map.Entry<String, Stats> e : stats.entrySet()) {
        String[] parts = e.getKey().split("\\|");
        Stats s = e.getValue();
        copy.row(
            parts[0],
            playerIds[Integer.parseInt(parts[1])],
            s.matches,
            s.total,
            s.best,
            s.wins,
            s.lastPlayed,
            0);
      }
      log.info("Seeded {} player_stats rows", copy.rows());
    }
  }

  /**
   * Points follow a log-normal curve (most players near the middle, a long tail of stars), which
   * also produces natural ties. Time trials are 15-120 seconds in milliseconds.
   */
  private static long randomScore(SortOrder order, Random random) {
    if (order == SortOrder.ASC) {
      return 15_000 + random.nextInt(105_000);
    }
    return Math.round(Math.exp(8.5 + random.nextGaussian() * 0.6));
  }
}
