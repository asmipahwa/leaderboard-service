package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.LeaderboardCache;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.Member;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.Page;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.State;
import com.asmipahwa.leaderboard.cache.LeaderboardRebuilder;
import com.asmipahwa.leaderboard.cache.PlayerNameCache;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.error.UnavailableException;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao;
import com.asmipahwa.leaderboard.service.Ranking.Scored;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read side of the leaderboards: top N, a player's rank with neighbours, and friends boards.
 *
 * <p>Every read is served from the Redis sorted set; Postgres is not touched on the hot path. If
 * the set is missing (cold start, Redis restart) it is rebuilt from Postgres once, behind a lock,
 * while other readers wait briefly instead of all hitting the database.
 */
@Service
public class LeaderboardQueryService {

  static final int DEFAULT_TOP_N = 10;
  private static final int READY_WAIT_ATTEMPTS = 80;
  private static final long READY_WAIT_MILLIS = 25;

  private final LeaderboardRegistry registry;
  private final LeaderboardCache cache;
  private final LeaderboardRebuilder rebuilder;
  private final PlayerNameCache names;
  private final FriendsService friends;
  private final ScoreQueryDao scoreQueries;
  private final Clock clock;

  public LeaderboardQueryService(
      LeaderboardRegistry registry,
      LeaderboardCache cache,
      LeaderboardRebuilder rebuilder,
      PlayerNameCache names,
      FriendsService friends,
      ScoreQueryDao scoreQueries,
      Clock clock) {
    this.registry = registry;
    this.cache = cache;
    this.rebuilder = rebuilder;
    this.names = names;
    this.friends = friends;
    this.scoreQueries = scoreQueries;
    this.clock = clock;
  }

  /** A page of the leaderboard, best first. This is the most frequent call in the service. */
  public TopNResult topN(String gameId, String leaderboardId, String periodKey, int n, int offset) {
    ResolvedBoard board = resolve(gameId, leaderboardId, periodKey);
    int size = Validation.pageSize(n, DEFAULT_TOP_N);
    int start = Validation.offset(offset);

    Page page = cache.page(board.key(), start, start + size - 1L);
    if (page.state() != State.READY) {
      if (!ensureReady(board)) {
        return topNFromDatabase(board, start, size);
      }
      page = cache.page(board.key(), start, start + size - 1L);
    }
    List<Member> members = page.members();
    long firstRank =
        start == 0 || members.isEmpty()
            ? 1
            : cache.rankOf(board.key(), members.get(0).rankingScore());
    return new TopNResult(rank(board, members, start, firstRank), page.total(), clock.instant());
  }

  /** The player's rank, the {@code around} players above and below, and their percentile. */
  public PlayerRankResult playerRank(
      String gameId, String leaderboardId, String periodKey, String playerId, int around) {
    ResolvedBoard board = resolve(gameId, leaderboardId, periodKey);
    String player = Validation.playerId(playerId).toString();
    int window = Validation.around(around);
    requireReady(board);

    double myScore =
        cache
            .rankingScore(board.key(), player)
            .orElseThrow(() -> new NotFoundException("Player has no score on this leaderboard"));
    long myRank = cache.rankOf(board.key(), myScore);
    long position = cache.position(board.key(), player).orElse(myRank - 1);
    long total = cache.size(board.key());

    long start = Math.max(0, position - window);
    List<Member> members = cache.range(board.key(), start, position + window);
    long firstRank =
        members.isEmpty() || members.get(0).playerId().equals(player)
            ? myRank
            : cache.rankOf(board.key(), members.get(0).rankingScore());
    List<RankedEntry> ranked = rank(board, members, start, firstRank);

    int me = -1;
    for (int i = 0; i < ranked.size(); i++) {
      if (ranked.get(i).playerId().equals(player)) {
        me = i;
        break;
      }
    }
    RankedEntry self =
        me >= 0
            ? ranked.get(me)
            : new RankedEntry(
                myRank,
                player,
                names.nameOf(UUID.fromString(player)),
                board.sortOrder().fromRankingScore(myScore));
    List<RankedEntry> above = me >= 0 ? ranked.subList(0, me) : List.of();
    List<RankedEntry> below = me >= 0 ? ranked.subList(me + 1, ranked.size()) : List.of();
    return new PlayerRankResult(
        self, List.copyOf(above), List.copyOf(below), Ranking.percentile(myRank, total));
  }

  /** The player and their friends, ranked among themselves. */
  public TopNResult friendsLeaderboard(
      String gameId, String leaderboardId, String periodKey, String playerId) {
    ResolvedBoard board = resolve(gameId, leaderboardId, periodKey);
    UUID player = Validation.playerId(playerId);
    if (names.nameOf(player) == null) {
      throw new NotFoundException("Unknown player " + player);
    }
    requireReady(board);

    List<String> ids = new ArrayList<>(friends.friendsOf(player));
    ids.add(player.toString());
    List<Double> scores = cache.rankingScores(board.key(), ids);
    List<Member> members = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      if (scores.get(i) != null) {
        members.add(new Member(ids.get(i), scores.get(i)));
      }
    }
    // Same order as the global board: best first, ties by player id descending.
    members.sort(
        Comparator.comparingDouble(Member::rankingScore)
            .thenComparing(Member::playerId)
            .reversed());
    return new TopNResult(rank(board, members, 0, 1), members.size(), clock.instant());
  }

  /**
   * Makes sure the board's sorted set is complete, rebuilding it from Postgres if needed. Only one
   * caller (across all pods) rebuilds; the others poll until it is ready.
   *
   * @return false if the board still isn't ready after about two seconds
   */
  public boolean ensureReady(ResolvedBoard board) {
    for (int attempt = 0; attempt < READY_WAIT_ATTEMPTS; attempt++) {
      State state = cache.state(board.key());
      if (state == State.READY) {
        return true;
      }
      if (state == State.MISSING && rebuilder.rebuild(board) >= 0) {
        return true;
      }
      sleep(READY_WAIT_MILLIS);
    }
    return false;
  }

  private void requireReady(ResolvedBoard board) {
    if (!ensureReady(board)) {
      throw new UnavailableException("Leaderboard " + board.key() + " is being rebuilt");
    }
  }

  private TopNResult topNFromDatabase(ResolvedBoard board, int offset, int size) {
    var key = board.key();
    List<RankedEntry> entries =
        scoreQueries.topN(
            key.gameId(), key.leaderboardId(), key.periodKey(), board.sortOrder(), offset, size);
    long total = scoreQueries.count(key.gameId(), key.leaderboardId(), key.periodKey());
    return new TopNResult(entries, total, clock.instant());
  }

  private List<RankedEntry> rank(
      ResolvedBoard board, List<Member> members, long firstPosition, long firstRank) {
    SortOrder order = board.sortOrder();
    List<Scored> scored = new ArrayList<>(members.size());
    List<String> ids = new ArrayList<>(members.size());
    for (Member member : members) {
      scored.add(new Scored(member.playerId(), order.fromRankingScore(member.rankingScore())));
      ids.add(member.playerId());
    }
    Map<String, String> displayNames = names.namesOf(ids);
    return Ranking.assign(scored, firstPosition, firstRank, displayNames);
  }

  private ResolvedBoard resolve(String gameId, String leaderboardId, String periodKey) {
    return registry.resolve(
        Validation.id(gameId, "game_id"),
        Validation.id(leaderboardId, "leaderboard_id"),
        Validation.period(periodKey));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("Interrupted while waiting for leaderboard rebuild");
    }
  }
}
