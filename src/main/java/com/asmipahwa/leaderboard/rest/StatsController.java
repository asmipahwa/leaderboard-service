package com.asmipahwa.leaderboard.rest;

import com.asmipahwa.leaderboard.service.PlayerStatsService;
import com.asmipahwa.leaderboard.service.PlayerStatsService.HistoryPage;
import com.asmipahwa.leaderboard.service.PlayerStatsService.StatsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST facade for player stats and match history. */
@RestController
@RequestMapping("/api/v1/games/{gameId}/players/{playerId}")
@Tag(name = "Player stats")
public class StatsController {

  private final PlayerStatsService stats;

  public StatsController(PlayerStatsService stats) {
    this.stats = stats;
  }

  @Operation(summary = "Lifetime stats for a player in one game")
  @GetMapping("/stats")
  public StatsView stats(@PathVariable String gameId, @PathVariable String playerId) {
    return stats.stats(gameId, playerId);
  }

  @Operation(summary = "Match history, newest first, paginated")
  @GetMapping("/history")
  public HistoryPage history(
      @PathVariable String gameId,
      @PathVariable String playerId,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String pageToken) {
    return stats.history(gameId, playerId, pageSize, pageToken);
  }
}
