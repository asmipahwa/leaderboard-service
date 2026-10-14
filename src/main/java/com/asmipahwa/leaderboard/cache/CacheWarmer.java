package com.asmipahwa.leaderboard.cache;

import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.repository.LeaderboardRepository;
import com.asmipahwa.leaderboard.service.LeaderboardRegistry;
import com.asmipahwa.leaderboard.service.ResolvedBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Builds any current-period leaderboard that is missing from Redis when the service starts.
 *
 * <p>Application runners finish before Spring reports the app as ready, so on Kubernetes a new pod
 * only receives traffic once the boards are warm. Players never pay for a cold cache after a deploy
 * or a Redis restart.
 */
@Component
@Profile("!seed")
public class CacheWarmer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

  private final LeaderboardRepository leaderboards;
  private final LeaderboardRegistry registry;
  private final LeaderboardCache cache;
  private final LeaderboardRebuilder rebuilder;
  private final boolean enabled;

  public CacheWarmer(
      LeaderboardRepository leaderboards,
      LeaderboardRegistry registry,
      LeaderboardCache cache,
      LeaderboardRebuilder rebuilder,
      @Value("${leaderboard.cache.warm-on-startup:true}") boolean enabled) {
    this.leaderboards = leaderboards;
    this.registry = registry;
    this.cache = cache;
    this.rebuilder = rebuilder;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    for (Leaderboard board : leaderboards.findAll()) {
      ResolvedBoard resolved = registry.resolve(board.getGameId(), board.getLeaderboardId(), null);
      try {
        if (cache.state(resolved.key()) == LeaderboardCache.State.MISSING) {
          rebuilder.rebuild(resolved);
        }
      } catch (RuntimeException e) {
        // A cold board is slower, not broken: reads rebuild it on demand or fall back to SQL.
        log.warn("Could not warm leaderboard {}", resolved.key(), e);
      }
    }
  }
}
