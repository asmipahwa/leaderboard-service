package com.asmipahwa.leaderboard.grpc;

import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetScoreHistoryResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardEntry;
import com.asmipahwa.leaderboard.grpc.v1.PlayerStats;
import com.asmipahwa.leaderboard.grpc.v1.ScoreEvent;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreResponse;
import com.asmipahwa.leaderboard.service.PlayerRankResult;
import com.asmipahwa.leaderboard.service.PlayerStatsService.HistoryPage;
import com.asmipahwa.leaderboard.service.PlayerStatsService.StatsView;
import com.asmipahwa.leaderboard.service.RankedEntry;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitCommand;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitResult;
import com.asmipahwa.leaderboard.service.TopNResult;
import com.google.protobuf.Timestamp;
import java.time.Instant;

/**
 * Converts between protobuf messages and service types. Keeping protobuf out of the service layer
 * means the REST facade and tests use the same plain Java records.
 */
final class ProtoMapper {

  private ProtoMapper() {}

  static SubmitCommand toCommand(SubmitScoreRequest request) {
    return new SubmitCommand(
        request.getLeaderboard().getGameId(),
        request.getLeaderboard().getLeaderboardId(),
        request.getLeaderboard().getPeriodKey(),
        request.getPlayerId(),
        request.getScore(),
        request.getIdempotencyKey(),
        request.getWin(),
        request.getPlatform());
  }

  static SubmitScoreResponse toProto(SubmitResult result) {
    return SubmitScoreResponse.newBuilder()
        .setImproved(result.improved())
        .setBestScore(result.bestScore())
        .setRank(result.rank())
        .setPreviousRank(result.previousRank())
        .addAllUnlockedAchievements(result.unlockedAchievements())
        .build();
  }

  static LeaderboardEntry toProto(RankedEntry entry) {
    return LeaderboardEntry.newBuilder()
        .setRank(entry.rank())
        .setPlayerId(entry.playerId())
        .setDisplayName(entry.displayName() == null ? "" : entry.displayName())
        .setScore(entry.score())
        .build();
  }

  static GetTopNResponse toProto(TopNResult result) {
    GetTopNResponse.Builder builder =
        GetTopNResponse.newBuilder()
            .setTotalPlayers(result.totalPlayers())
            .setEntriesVersion(result.entriesVersion())
            .setAsOf(toTimestamp(result.asOf()));
    for (RankedEntry entry : result.entries()) {
      builder.addEntries(toProto(entry));
    }
    return builder.build();
  }

  static GetPlayerRankResponse toProto(PlayerRankResult result) {
    GetPlayerRankResponse.Builder builder =
        GetPlayerRankResponse.newBuilder()
            .setMe(toProto(result.me()))
            .setPercentile(result.percentile());
    result.above().forEach(entry -> builder.addAbove(toProto(entry)));
    result.below().forEach(entry -> builder.addBelow(toProto(entry)));
    return builder.build();
  }

  static PlayerStats toProto(StatsView stats) {
    PlayerStats.Builder builder =
        PlayerStats.newBuilder()
            .setPlayerId(stats.playerId())
            .setGameId(stats.gameId())
            .setMatchesPlayed(stats.matchesPlayed())
            .setTotalScore(stats.totalScore())
            .setBestScore(stats.bestScore())
            .setWins(stats.wins())
            .setWinRate(stats.winRate())
            .addAllAchievements(stats.achievements());
    if (stats.lastPlayedAt() != null) {
      builder.setLastPlayedAt(toTimestamp(stats.lastPlayedAt()));
    }
    return builder.build();
  }

  static GetScoreHistoryResponse toProto(HistoryPage page) {
    GetScoreHistoryResponse.Builder builder =
        GetScoreHistoryResponse.newBuilder().setNextPageToken(page.nextPageToken());
    page.events()
        .forEach(
            event ->
                builder.addEvents(
                    ScoreEvent.newBuilder()
                        .setScore(event.score())
                        .setAccepted(event.accepted())
                        .setLeaderboardId(event.leaderboardId())
                        .setReceivedAt(toTimestamp(event.receivedAt()))));
    return builder.build();
  }

  static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }
}
