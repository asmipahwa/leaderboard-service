package com.asmipahwa.leaderboard.rest;

import com.asmipahwa.leaderboard.service.LeaderboardQueryService;
import com.asmipahwa.leaderboard.service.PlayerRankResult;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitCommand;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitResult;
import com.asmipahwa.leaderboard.service.TopNResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST facade over the same services the gRPC API uses, for browsers, curl and the demo page.
 * gRPC stays the primary contract; this class adds no business logic.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}/leaderboards/{leaderboardId}")
@Tag(name = "Leaderboards")
public class LeaderboardController {

  /** JSON body for a score submission, checked with Bean Validation before reaching the service. */
  public record SubmitScoreBody(
      @NotBlank String playerId,
      @NotNull @Min(0) Long score,
      @NotBlank @Size(max = 128) String idempotencyKey,
      boolean win) {}

  private final LeaderboardQueryService queries;
  private final ScoreSubmissionService submissions;

  public LeaderboardController(
      LeaderboardQueryService queries, ScoreSubmissionService submissions) {
    this.queries = queries;
    this.submissions = submissions;
  }

  @Operation(summary = "Top N players, best first")
  @GetMapping("/top")
  public TopNResult top(
      @PathVariable String gameId,
      @PathVariable String leaderboardId,
      @RequestParam(defaultValue = "10") @Min(1) @Max(1000) int n,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String period) {
    return queries.topN(gameId, leaderboardId, period, n, offset);
  }

  @Operation(summary = "A player's rank plus the players around them")
  @GetMapping("/players/{playerId}/rank")
  public PlayerRankResult rank(
      @PathVariable String gameId,
      @PathVariable String leaderboardId,
      @PathVariable String playerId,
      @RequestParam(defaultValue = "2") int around,
      @RequestParam(defaultValue = "") String period) {
    return queries.playerRank(gameId, leaderboardId, period, playerId, around);
  }

  @Operation(summary = "A player and their friends, ranked among themselves")
  @GetMapping("/players/{playerId}/friends")
  public TopNResult friends(
      @PathVariable String gameId,
      @PathVariable String leaderboardId,
      @PathVariable String playerId,
      @RequestParam(defaultValue = "") String period) {
    return queries.friendsLeaderboard(gameId, leaderboardId, period, playerId);
  }

  @Operation(summary = "Submit a match result")
  @PostMapping("/scores")
  public SubmitResult submit(
      @PathVariable String gameId,
      @PathVariable String leaderboardId,
      @Valid @RequestBody SubmitScoreBody body) {
    return submissions.submit(
        new SubmitCommand(
            gameId,
            leaderboardId,
            "",
            body.playerId(),
            body.score(),
            body.idempotencyKey(),
            body.win()));
  }
}
