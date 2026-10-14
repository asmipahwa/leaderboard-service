package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.PlayerNameCache;
import com.asmipahwa.leaderboard.domain.PlayerStatsEntity;
import com.asmipahwa.leaderboard.domain.PlayerStatsId;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.repository.GameRepository;
import com.asmipahwa.leaderboard.repository.PlayerStatsRepository;
import com.asmipahwa.leaderboard.repository.ScoreHistoryDao;
import com.asmipahwa.leaderboard.repository.ScoreHistoryDao.EventRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Player progress: lifetime stats per game and paginated match history. These are profile-screen
 * reads, so they go straight to Postgres; only rankings need Redis speed.
 */
@Service
public class PlayerStatsService {

  /** Stats as shown on a profile screen. */
  public record StatsView(
      String playerId,
      String gameId,
      long matchesPlayed,
      long totalScore,
      long bestScore,
      long wins,
      double winRate,
      Instant lastPlayedAt,
      List<String> achievements) {}

  /** One page of history plus the cursor for the next page (empty when there is none). */
  public record HistoryPage(List<EventRow> events, String nextPageToken) {}

  private static final int DEFAULT_HISTORY_PAGE = 20;
  private static final int MAX_HISTORY_PAGE = 100;

  private final GameRepository games;
  private final PlayerStatsRepository stats;
  private final ScoreHistoryDao history;
  private final PlayerNameCache names;

  public PlayerStatsService(
      GameRepository games,
      PlayerStatsRepository stats,
      ScoreHistoryDao history,
      PlayerNameCache names) {
    this.games = games;
    this.stats = stats;
    this.history = history;
    this.names = names;
  }

  public StatsView stats(String gameId, String playerId) {
    String game = requireGame(gameId);
    UUID player = requirePlayer(playerId);
    PlayerStatsEntity row =
        stats.findById(new PlayerStatsId(game, player)).orElse(new PlayerStatsEntity(game, player));
    double winRate =
        row.getMatchesPlayed() == 0 ? 0.0 : (double) row.getWins() / row.getMatchesPlayed();
    return new StatsView(
        player.toString(),
        game,
        row.getMatchesPlayed(),
        row.getTotalScore(),
        row.getBestScore(),
        row.getWins(),
        winRate,
        row.getLastPlayedAt(),
        List.of());
  }

  public HistoryPage history(String gameId, String playerId, int pageSize, String pageToken) {
    String game = requireGame(gameId);
    UUID player = requirePlayer(playerId);
    int size = Math.min(Validation.pageSize(pageSize, DEFAULT_HISTORY_PAGE), MAX_HISTORY_PAGE);

    Cursor cursor = Cursor.decode(pageToken);
    // Ask for one extra row to know whether another page exists.
    List<EventRow> rows =
        history.page(
            game,
            player,
            cursor == null ? null : cursor.time(),
            cursor == null ? 0 : cursor.id(),
            size + 1);
    if (rows.size() <= size) {
      return new HistoryPage(rows, "");
    }
    List<EventRow> page = rows.subList(0, size);
    EventRow last = page.get(size - 1);
    return new HistoryPage(
        List.copyOf(page), new Cursor(last.receivedAt(), last.eventId()).encode());
  }

  private String requireGame(String gameId) {
    String game = Validation.id(gameId, "game_id");
    if (!games.existsById(game)) {
      throw new NotFoundException("Unknown game " + game);
    }
    return game;
  }

  private UUID requirePlayer(String playerId) {
    UUID player = Validation.playerId(playerId);
    if (names.nameOf(player) == null) {
      throw new NotFoundException("Unknown player " + player);
    }
    return player;
  }

  /** Opaque page token: base64 of "time|eventId" of the last row on the previous page. */
  record Cursor(Instant time, long id) {

    String encode() {
      String raw = time + "|" + id;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String token) {
      if (token == null || token.isEmpty()) {
        return null;
      }
      try {
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        return new Cursor(
            Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1)));
      } catch (RuntimeException e) {
        throw new InvalidRequestException("page_token is invalid");
      }
    }
  }
}
