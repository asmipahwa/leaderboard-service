package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.BoardKey;
import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.domain.LeaderboardId;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.repository.LeaderboardRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory lookup of leaderboard definitions.
 *
 * <p>Board definitions almost never change but are needed on every request (sort order, reset
 * period), so they are loaded from Postgres once and then served from memory. That keeps the read
 * hot path free of database calls.
 */
@Component
public class LeaderboardRegistry {

  private final LeaderboardRepository repository;
  private final PeriodKeyResolver periods;
  private final Map<LeaderboardId, Leaderboard> cache = new ConcurrentHashMap<>();

  public LeaderboardRegistry(LeaderboardRepository repository, PeriodKeyResolver periods) {
    this.repository = repository;
    this.periods = periods;
  }

  /** Returns the board definition or throws {@link NotFoundException}. */
  public Leaderboard get(String gameId, String leaderboardId) {
    LeaderboardId id = new LeaderboardId(gameId, leaderboardId);
    Leaderboard board = cache.get(id);
    if (board == null) {
      board =
          repository
              .findById(id)
              .orElseThrow(
                  () ->
                      new NotFoundException("Unknown leaderboard " + gameId + "/" + leaderboardId));
      cache.put(id, board);
    }
    return board;
  }

  /**
   * Resolves a board and period. A blank {@code periodKey} means the current period, which is what
   * clients want almost all the time.
   */
  public ResolvedBoard resolve(String gameId, String leaderboardId, String periodKey) {
    Leaderboard board = get(gameId, leaderboardId);
    String period =
        periodKey == null || periodKey.isBlank()
            ? periods.current(board.getResetPeriod())
            : periodKey;
    return new ResolvedBoard(board, new BoardKey(gameId, leaderboardId, period));
  }
}
