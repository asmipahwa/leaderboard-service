package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.LeaderboardCache;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.Member;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.Page;
import com.asmipahwa.leaderboard.cache.LeaderboardCache.State;
import com.asmipahwa.leaderboard.cache.LeaderboardRebuilder;
import com.asmipahwa.leaderboard.cache.PlayerNameCache;
import com.asmipahwa.leaderboard.cache.RedisGuard;
import com.asmipahwa.leaderboard.domain.SortOrder;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.error.UnavailableException;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao;
import com.asmipahwa.leaderboard.repository.ScoreQueryDao.ScoreRow;
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
 * while other readers wait briefly instead of all hitting the database. If Redis itself is down,
 * {@link RedisGuard} switches every read to an equivalent (slower) Postgres query, so players still
 * see their leaderboards.
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
  private final RedisGuard guard;
  private final Clock clock;

  public LeaderboardQueryService(
      LeaderboardRegistry registry,
      LeaderboardCache cache,
      LeaderboardRebuilder rebuilder,
      PlayerNameCache names,
      FriendsService friends,
      ScoreQueryDao scoreQueries,
      RedisGuard guard,
      Clock clock) {
    this.registry = registry;
    this.cache = cache;
    this.rebuilder = rebuilder;
    this.names = names;
    this.friends = friends;
    this.scoreQueries = scoreQueries;
    this.guard = guard;
    this.clock = clock;
  }

  /** A page of the leaderboard, best first. This is the most frequent call in the service. */
  public TopNResult topN(String gameId, String leaderboardId, String periodKey, int n, int offset) {
    ResolvedBoard board = resolve(gameId, leaderboardId, periodKey);
    int size = Validation.pageSize(n, DEFAULT_TOP_N);
    int start = Validation.offset(offset);
    return guard.call(
        "top_n",
        () -> topNFromRedis(board, start, size),
        () -> topNFromDatabase(board, start, size));
  }

  private TopNResult topNFromRedis(ResolvedBoard board, int start, int size) {
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
    UUID player = Validation.playerId(playerId);
    int window = Validation.around(around);
    return guard.call(
        "player_rank",
        () -> playerRankFromRedis(board, player.toString(), window),
        () -> playerRankFromDatabase(board, player, window));
  }

  private PlayerRankResult playerRankFromRedis(ResolvedBoard board, String player, int window) {
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
    RankedEntry fallbackSelf =
        new RankedEntry(myRank, player, null, board.sortOrder().fromRankingScore(myScore));
    return split(rank(board, members, start, firstRank), fallbackSelf, total);
  }

  /** Splits a ranked window into the player, the entries above and the entries below. */
  private PlayerRankResult split(List<RankedEntry> ranked, RankedEntry fallbackSelf, long total) {
    String player = fallbackSelf.playerId();
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
                fallbackSelf.rank(),
                player,
                names.nameOf(UUID.fromString(player)),
                fallbackSelf.score());
    List<RankedEntry> above = me >= 0 ? ranked.subList(0, me) : List.of();
    List<RankedEntry> below = me >= 0 ? ranked.subList(me + 1, ranked.size()) : List.of();
    return new PlayerRankResult(
        self, List.copyOf(above), List.copyOf(below), Ranking.percentile(self.rank(), total));
  }

  private PlayerRankResult playerRankFromDatabase(ResolvedBoard board, UUID player, int window) {
    var key = board.key();
    long myScore =
        scoreQueries
            .bestScore(key.gameId(), key.leaderboardId(), key.periodKey(), player)
            .orElseThrow(() -> new NotFoundException("Player has no score on this leaderboard"));
    long myRank = rankFromDatabase(board, myScore);
    long position =
        scoreQueries.position(
            key.gameId(), key.leaderboardId(), key.periodKey(), board.sortOrder(), player, myScore);
    int start = (int) Math.max(0, position - window);
    List<RankedEntry> ranked =
        scoreQueries.topN(
            key.gameId(),
            key.leaderboardId(),
            key.periodKey(),
            board.sortOrder(),
            start,
            (int) (position - start) + window + 1);
    long total = scoreQueries.count(key.gameId(), key.leaderboardId(), key.periodKey());
    return split(ranked, new RankedEntry(myRank, player.toString(), null, myScore), total);
  }

  /** Competition rank of a score computed in Postgres (used when Redis is unavailable). */
  public long rankFromDatabase(ResolvedBoard board, long score) {
    var key = board.key();
    return scoreQueries.countBetter(
            key.gameId(), key.leaderboardId(), key.periodKey(), board.sortOrder(), score)
        + 1;
  }

  /** The player and their friends, ranked among themselves. */
  public TopNResult friendsLeaderboard(
      String gameId, String leaderboardId, String periodKey, String playerId) {
    ResolvedBoard board = resolve(gameId, leaderboardId, periodKey);
    UUID player = Validation.playerId(playerId);
    if (names.nameOf(player) == null) {
      throw new NotFoundException("Unknown player " + player);
    }
    return guard.call(
        "friends", () -> friendsFromRedis(board, player), () -> friendsFromDatabase(board, player));
  }

  private TopNResult friendsFromRedis(ResolvedBoard board, UUID player) {
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

  private TopNResult friendsFromDatabase(ResolvedBoard board, UUID player) {
    var key = board.key();
    List<UUID> ids = new ArrayList<>();
    friends.friendsFromDatabase(player).forEach(id -> ids.add(UUID.fromString(id)));
    ids.add(player);
    SortOrder order = board.sortOrder();
    List<Member> members = new ArrayList<>();
    for (ScoreRow row :
        scoreQueries.scoresOf(key.gameId(), key.leaderboardId(), key.periodKey(), ids)) {
      members.add(new Member(row.playerId().toString(), order.toRankingScore(row.bestScore())));
    }
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
