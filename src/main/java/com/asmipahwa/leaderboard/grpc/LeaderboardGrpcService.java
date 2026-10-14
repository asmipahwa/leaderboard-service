package com.asmipahwa.leaderboard.grpc;

import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.grpc.v1.BatchSubmitScoresRequest;
import com.asmipahwa.leaderboard.grpc.v1.BatchSubmitScoresResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetFriendsLeaderboardRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankResponse;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardRef;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.StreamTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreResponse;
import com.asmipahwa.leaderboard.service.LeaderboardQueryService;
import com.asmipahwa.leaderboard.service.LeaderboardRegistry;
import com.asmipahwa.leaderboard.service.LiveLeaderboardHub;
import com.asmipahwa.leaderboard.service.LiveLeaderboardHub.Subscription;
import com.asmipahwa.leaderboard.service.ResolvedBoard;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService;
import com.asmipahwa.leaderboard.service.Validation;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC endpoint for leaderboards, the primary API game clients and servers call.
 *
 * <p>This class only translates protobuf to service calls and back. Errors are thrown as domain
 * exceptions and turned into gRPC status codes by {@link GrpcExceptionAdvice}.
 */
@GrpcService
public class LeaderboardGrpcService extends LeaderboardServiceGrpc.LeaderboardServiceImplBase {

  static final int MAX_BATCH = 100;
  private static final Logger log = LoggerFactory.getLogger(LeaderboardGrpcService.class);

  private final ScoreSubmissionService submissions;
  private final LeaderboardQueryService queries;
  private final LeaderboardRegistry registry;
  private final LiveLeaderboardHub hub;

  public LeaderboardGrpcService(
      ScoreSubmissionService submissions,
      LeaderboardQueryService queries,
      LeaderboardRegistry registry,
      LiveLeaderboardHub hub) {
    this.submissions = submissions;
    this.queries = queries;
    this.registry = registry;
    this.hub = hub;
  }

  @Override
  public void submitScore(
      SubmitScoreRequest request, StreamObserver<SubmitScoreResponse> responseObserver) {
    try (var ignored = LogContext.of(request.getLeaderboard().getGameId(), request.getPlayerId())) {
      responseObserver.onNext(
          ProtoMapper.toProto(submissions.submit(ProtoMapper.toCommand(request))));
      responseObserver.onCompleted();
    }
  }

  @Override
  public void batchSubmitScores(
      BatchSubmitScoresRequest request,
      StreamObserver<BatchSubmitScoresResponse> responseObserver) {
    if (request.getSubmissionsCount() > MAX_BATCH) {
      throw new InvalidRequestException("At most " + MAX_BATCH + " submissions per batch");
    }
    BatchSubmitScoresResponse.Builder response = BatchSubmitScoresResponse.newBuilder();
    for (SubmitScoreRequest submission : request.getSubmissionsList()) {
      response.addResults(
          ProtoMapper.toProto(submissions.submit(ProtoMapper.toCommand(submission))));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void getTopN(GetTopNRequest request, StreamObserver<GetTopNResponse> responseObserver) {
    LeaderboardRef ref = request.getLeaderboard();
    responseObserver.onNext(
        ProtoMapper.toProto(
            queries.topN(
                ref.getGameId(),
                ref.getLeaderboardId(),
                ref.getPeriodKey(),
                request.getN(),
                request.getOffset())));
    responseObserver.onCompleted();
  }

  @Override
  public void getPlayerRank(
      GetPlayerRankRequest request, StreamObserver<GetPlayerRankResponse> responseObserver) {
    LeaderboardRef ref = request.getLeaderboard();
    try (var ignored = LogContext.of(ref.getGameId(), request.getPlayerId())) {
      playerRank(request, ref, responseObserver);
    }
  }

  private void playerRank(
      GetPlayerRankRequest request,
      LeaderboardRef ref,
      StreamObserver<GetPlayerRankResponse> responseObserver) {
    responseObserver.onNext(
        ProtoMapper.toProto(
            queries.playerRank(
                ref.getGameId(),
                ref.getLeaderboardId(),
                ref.getPeriodKey(),
                request.getPlayerId(),
                request.getAround())));
    responseObserver.onCompleted();
  }

  @Override
  public void getFriendsLeaderboard(
      GetFriendsLeaderboardRequest request, StreamObserver<GetTopNResponse> responseObserver) {
    LeaderboardRef ref = request.getLeaderboard();
    responseObserver.onNext(
        ProtoMapper.toProto(
            queries.friendsLeaderboard(
                ref.getGameId(),
                ref.getLeaderboardId(),
                ref.getPeriodKey(),
                request.getPlayerId())));
    responseObserver.onCompleted();
  }

  /**
   * Sends the current top N, then a fresh snapshot whenever the board changes (at most 4 per
   * second). The stream stays open until the client cancels it.
   */
  @Override
  public void streamTopN(
      StreamTopNRequest request, StreamObserver<GetTopNResponse> responseObserver) {
    LeaderboardRef ref = request.getLeaderboard();
    ResolvedBoard board =
        registry.resolve(
            Validation.id(ref.getGameId(), "game_id"),
            Validation.id(ref.getLeaderboardId(), "leaderboard_id"),
            Validation.period(ref.getPeriodKey()));
    int n = request.getN();
    ServerCallStreamObserver<GetTopNResponse> observer =
        (ServerCallStreamObserver<GetTopNResponse>) responseObserver;

    Runnable push =
        () -> {
          GetTopNResponse snapshot =
              ProtoMapper.toProto(
                  queries.topN(
                      board.key().gameId(),
                      board.key().leaderboardId(),
                      board.key().periodKey(),
                      n,
                      0));
          // StreamObserver is not thread-safe; pushes can come from different threads.
          synchronized (observer) {
            if (!observer.isCancelled()) {
              observer.onNext(snapshot);
            }
          }
        };
    Subscription subscription = hub.subscribe(board.key(), push);
    observer.setOnCancelHandler(
        () -> {
          subscription.close();
          log.debug("StreamTopN subscriber left {}", board.key());
        });
    push.run();
  }
}
