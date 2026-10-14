package com.asmipahwa.leaderboard.grpc;

import com.asmipahwa.leaderboard.grpc.v1.GetPlayerStatsRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetScoreHistoryRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetScoreHistoryResponse;
import com.asmipahwa.leaderboard.grpc.v1.PlayerStats;
import com.asmipahwa.leaderboard.grpc.v1.PlayerStatsServiceGrpc;
import com.asmipahwa.leaderboard.service.PlayerStatsService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/** gRPC endpoint for player progress: lifetime stats and match history. */
@GrpcService
public class PlayerStatsGrpcService extends PlayerStatsServiceGrpc.PlayerStatsServiceImplBase {

  private final PlayerStatsService stats;

  public PlayerStatsGrpcService(PlayerStatsService stats) {
    this.stats = stats;
  }

  @Override
  public void getPlayerStats(
      GetPlayerStatsRequest request, StreamObserver<PlayerStats> responseObserver) {
    responseObserver.onNext(
        ProtoMapper.toProto(stats.stats(request.getGameId(), request.getPlayerId())));
    responseObserver.onCompleted();
  }

  @Override
  public void getScoreHistory(
      GetScoreHistoryRequest request, StreamObserver<GetScoreHistoryResponse> responseObserver) {
    responseObserver.onNext(
        ProtoMapper.toProto(
            stats.history(
                request.getGameId(),
                request.getPlayerId(),
                request.getPageSize(),
                request.getPageToken())));
    responseObserver.onCompleted();
  }
}
