package com.asmipahwa.leaderboard.load;

import static com.asmipahwa.leaderboard.load.LoadConfig.perUser;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.grpc;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.statusCode;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Choice;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sustained realistic traffic: 70% reads (top 100 / my rank) and 30% score submissions at {@code
 * rate} requests/s (default 200) for {@code duration} seconds (default 300). Passes if under 0.1%
 * of requests fail.
 */
public class MixedSimulation extends Simulation {

  private static final int RATE = LoadConfig.intProp("rate", 200);
  private static final int DURATION = LoadConfig.intProp("duration", 300);
  private static final List<String> PLAYERS = Fixtures.playerIds(5_000);

  private static String randomPlayer() {
    return PLAYERS.get(ThreadLocalRandom.current().nextInt(PLAYERS.size()));
  }

  private final ChainBuilder topN =
      exec(
          grpc("GetTopN(100)")
              .rpc(LeaderboardServiceGrpc.getGetTopNMethod())
              .payload(
                  GetTopNRequest.newBuilder()
                      .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                      .setN(100)
                      .build())
              .check(statusCode().is(Status.Code.OK)));

  private final ChainBuilder rank =
      exec(
          grpc("GetPlayerRank")
              .rpc(LeaderboardServiceGrpc.getGetPlayerRankMethod())
              .payload(
                  perUser(
                      session ->
                          GetPlayerRankRequest.newBuilder()
                              .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                              .setPlayerId(randomPlayer())
                              .build()))
              .check(statusCode().is(Status.Code.OK)));

  private final ChainBuilder submit =
      exec(
          grpc("SubmitScore")
              .rpc(LeaderboardServiceGrpc.getSubmitScoreMethod())
              .payload(
                  perUser(
                      session -> {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        return SubmitScoreRequest.newBuilder()
                            .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                            .setPlayerId(randomPlayer())
                            .setScore(Math.round(Math.exp(8.5 + random.nextGaussian() * 0.6)))
                            .setWin(random.nextBoolean())
                            .setIdempotencyKey("mixed-" + UUID.randomUUID())
                            .build();
                      }))
              .check(statusCode().is(Status.Code.OK)));

  private final ScenarioBuilder traffic =
      scenario("Mixed traffic")
          .randomSwitch()
          .on(
              Choice.withWeight(50.0, topN),
              Choice.withWeight(20.0, rank),
              Choice.withWeight(30.0, submit));

  {
    setUp(traffic.injectOpen(constantUsersPerSec(RATE).during(Duration.ofSeconds(DURATION))))
        .protocols(LoadConfig.protocol())
        .assertions(global().failedRequests().percent().lt(0.1));
  }
}
