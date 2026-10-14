package com.asmipahwa.leaderboard.debug;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deliberately slows down one query, to practise finding a latency problem with metrics, traces and
 * logs (see docs/OBSERVABILITY.md). Off by default; only reachable when the admin endpoint is
 * enabled with {@code leaderboard.admin.enabled=true}.
 *
 * <p>The delay runs as a real {@code pg_sleep} inside Postgres, so it shows up in traces exactly
 * like a genuinely slow query would.
 */
@Component
public class FaultInjector {

  private final JdbcTemplate jdbc;
  private final AtomicLong slowRankQueryMillis = new AtomicLong();

  public FaultInjector(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void setSlowRankQueryMillis(long millis) {
    slowRankQueryMillis.set(Math.max(0, Math.min(millis, 5_000)));
  }

  public long slowRankQueryMillis() {
    return slowRankQueryMillis.get();
  }

  /** Called on the GetPlayerRank path. Does nothing unless a delay was switched on. */
  public void maybeSlowDownRankQuery() {
    long millis = slowRankQueryMillis.get();
    if (millis > 0) {
      jdbc.queryForObject("SELECT pg_sleep(?) IS NULL", Boolean.class, millis / 1000.0);
    }
  }
}
