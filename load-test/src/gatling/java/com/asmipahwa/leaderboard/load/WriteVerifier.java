package com.asmipahwa.leaderboard.load;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Proves "zero lost writes" after {@link ConcurrentWriteSimulation}, by checking the real stores
 * rather than trusting response codes:
 *
 * <ol>
 *   <li>every SubmitScore that Gatling saw succeed has exactly one row in {@code score_events};
 *   <li>for every player the run touched, Postgres {@code best_score} equals the highest score
 *       ever submitted for them on that board (no lost update from a race);
 *   <li>their {@code matches_played} equals their number of events (no lost or double count);
 *   <li>Redis holds exactly the Postgres best score for each of them (cache agrees with truth).
 * </ol>
 *
 * Run with {@code ./gradlew :load-test:verifyWrites} (or {@code make load-write}).
 */
public final class WriteVerifier {

  private static final Pattern REQUESTS =
      Pattern.compile(
          "\"numberOfRequests\"\\s*:\\s*\\{\\s*\"total\"\\s*:\\s*(\\d+)\\s*,\\s*\"ok\"\\s*:\\s*(\\d+)\\s*,\\s*\"ko\"\\s*:\\s*(\\d+)");

  private WriteVerifier() {}

  public static void main(String[] args) throws Exception {
    List<String> run = Files.readAllLines(LoadConfig.LOAD_DIR.resolve("gatling-last-write-run.txt"));
    String runId = run.get(0);
    long retriesBefore = Long.parseLong(run.get(2));
    long[] gatling = gatlingCounts();
    String dbUrl = System.getProperty("db.url", "jdbc:postgresql://localhost:5433/leaderboard");
    String zset = "lb:" + LoadConfig.GAME + ":" + LoadConfig.BOARD + ":S1";

    long events;
    List<UUID> players = new ArrayList<>();
    int bestMismatches = 0;
    int statsMismatches = 0;
    int redisMismatches = 0;
    Map<UUID, Long> best = new HashMap<>();
    try (Connection db = DriverManager.getConnection(dbUrl, "leaderboard", "leaderboard")) {
      events = count(db, "SELECT count(*) FROM score_events WHERE idempotency_key LIKE ?", runId + "-%");
      try (PreparedStatement ps =
          db.prepareStatement(
              "SELECT DISTINCT player_id FROM score_events WHERE idempotency_key LIKE ?")) {
        ps.setString(1, runId + "-%");
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            players.add(rs.getObject(1, UUID.class));
          }
        }
      }
      Array ids = db.createArrayOf("uuid", players.toArray());

      try (PreparedStatement ps =
          db.prepareStatement(
              "SELECT s.player_id, s.best_score, (SELECT max(e.score) FROM score_events e"
                  + " WHERE e.game_id = s.game_id AND e.leaderboard_id = s.leaderboard_id"
                  + " AND e.player_id = s.player_id) AS max_submitted FROM scores s"
                  + " WHERE s.game_id = ? AND s.leaderboard_id = ? AND s.period_key = 'S1'"
                  + " AND s.player_id = ANY (?)")) {
        ps.setString(1, LoadConfig.GAME);
        ps.setString(2, LoadConfig.BOARD);
        ps.setArray(3, ids);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            best.put(rs.getObject(1, UUID.class), rs.getLong(2));
            if (rs.getLong(2) != rs.getLong(3)) {
              bestMismatches++;
            }
          }
        }
      }
      bestMismatches += players.size() - best.size(); // a touched player with no score row

      try (PreparedStatement ps =
          db.prepareStatement(
              "SELECT ps.matches_played, (SELECT count(*) FROM score_events e"
                  + " WHERE e.game_id = ps.game_id AND e.player_id = ps.player_id)"
                  + " FROM player_stats ps WHERE ps.game_id = ? AND ps.player_id = ANY (?)")) {
        ps.setString(1, LoadConfig.GAME);
        ps.setArray(2, ids);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            if (rs.getLong(1) != rs.getLong(2)) {
              statsMismatches++;
            }
          }
        }
      }
    }

    RedisClient client = RedisClient.create(System.getProperty("redis.uri", "redis://localhost:6379"));
    try (StatefulRedisConnection<String, String> redis = client.connect()) {
      List<String> keys = players.stream().map(UUID::toString).toList();
      var scores = redis.sync().zmscore(zset, keys.toArray(String[]::new));
      for (int i = 0; i < players.size(); i++) {
        Double cached = scores.get(i);
        Long truth = best.get(players.get(i));
        if (cached == null || truth == null || cached.longValue() != truth) {
          redisMismatches++;
        }
      }
    } finally {
      client.shutdown();
    }

    long retries = optimisticLockRetries() - retriesBefore;
    long missingEvents = gatling[1] - events;
    long lostWrites = Math.max(0, missingEvents) + bestMismatches + statsMismatches;
    String summary =
        String.format(
            "WriteVerifier run=%s%n"
                + "  gatling SubmitScore requests: total=%d ok=%d ko=%d%n"
                + "  score_events rows for this run: %d (missing for OK responses: %d)%n"
                + "  players touched: %d%n"
                + "  best_score != max submitted: %d%n"
                + "  matches_played != event count: %d%n"
                + "  redis score != postgres best: %d%n"
                + "  optimistic-lock retries during run: %d%n"
                + "  LOST WRITES: %d%n",
            runId,
            gatling[0],
            gatling[1],
            gatling[2],
            events,
            missingEvents,
            players.size(),
            bestMismatches,
            statsMismatches,
            redisMismatches,
            retries,
            lostWrites);
    System.out.print(summary);
    Path out = LoadConfig.LOAD_DIR.resolve("evidence").resolve("write-verification-" + runId + ".txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, summary);
    if (lostWrites > 0 || redisMismatches > 0 || events != gatling[1]) {
      System.exit(1);
    }
  }

  /** Total/ok/ko request counts from the newest ConcurrentWriteSimulation report. */
  private static long[] gatlingCounts() throws Exception {
    Path report;
    try (Stream<Path> dirs = Files.list(LoadConfig.LOAD_DIR.resolve("reports").resolve("gatling"))) {
      report =
          dirs.filter(p -> p.getFileName().toString().startsWith("concurrentwritesimulation"))
              .max(Comparator.comparing(p -> p.toFile().lastModified()))
              .orElseThrow();
    }
    Matcher m = REQUESTS.matcher(Files.readString(report.resolve("js").resolve("stats.json")));
    if (!m.find()) {
      throw new IllegalStateException("No request counts in " + report);
    }
    return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))};
  }

  private static long count(Connection db, String sql, String arg) throws Exception {
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Current value of the service's retry counter, read from its Prometheus endpoint. */
  static long optimisticLockRetries() {
    String url = System.getProperty("metrics.url", "http://localhost:8080/actuator/prometheus");
    try {
      String body =
          HttpClient.newHttpClient()
              .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
              .body();
      Matcher m =
          Pattern.compile("(?m)^leaderboard_optimistic_lock_retries_total\\{[^}]*\\} ([0-9.E]+)$")
              .matcher(body);
      return m.find() ? (long) Double.parseDouble(m.group(1)) : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
