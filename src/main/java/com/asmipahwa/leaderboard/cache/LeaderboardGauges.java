package com.asmipahwa.leaderboard.cache;

import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.repository.LeaderboardRepository;
import com.asmipahwa.leaderboard.service.LeaderboardRegistry;
import com.asmipahwa.leaderboard.service.ResolvedBoard;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code leaderboard_players{board}}: how many players are ranked on each current board.
 *
 * <p>The value is refreshed on a timer (one {@code ZCARD} per board) rather than at scrape time, so
 * a slow or unreachable Redis can never make the {@code /actuator/prometheus} endpoint hang.
 */
@Component
@Profile("!seed")
public class LeaderboardGauges {

  private final LeaderboardRepository leaderboards;
  private final LeaderboardRegistry registry;
  private final LeaderboardCache cache;
  private final MeterRegistry meters;
  private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

  public LeaderboardGauges(
      LeaderboardRepository leaderboards,
      LeaderboardRegistry registry,
      LeaderboardCache cache,
      MeterRegistry meters) {
    this.leaderboards = leaderboards;
    this.registry = registry;
    this.cache = cache;
    this.meters = meters;
  }

  @Scheduled(fixedDelayString = "${leaderboard.metrics.gauge-refresh-ms:15000}")
  public void refresh() {
    for (Leaderboard definition : leaderboards.findAll()) {
      ResolvedBoard board =
          registry.resolve(definition.getGameId(), definition.getLeaderboardId(), null);
      String name = definition.getGameId() + "/" + definition.getLeaderboardId();
      try {
        gauge(name).set(cache.size(board.key()));
      } catch (DataAccessException e) {
        // Keep the last known value while Redis is unavailable.
      }
    }
  }

  private AtomicLong gauge(String board) {
    return values.computeIfAbsent(
        board,
        name -> {
          AtomicLong value = new AtomicLong();
          Gauge.builder("leaderboard.players", value, AtomicLong::get)
              .description("Players ranked on the current period of each leaderboard")
              .tag("board", name)
              .register(meters);
          return value;
        });
  }
}
