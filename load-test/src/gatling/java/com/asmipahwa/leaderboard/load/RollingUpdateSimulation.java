package com.asmipahwa.leaderboard.load;

import static com.asmipahwa.leaderboard.load.LoadConfig.perUser;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.grpc;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.statusCode;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;

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
 * Backs the resume claim "zero-downtime rolling updates on Kubernetes".
 *
 * <p>Steady traffic ({@code rate} req/s, default 50) for {@code duration} seconds (default 180)
 * while {@code kubectl rollout restart} replaces every pod. Passes only if not a single request
 * fails.
 */
public class RollingUpdateSimulation extends Simulation {

  private static final int RATE = LoadConfig.intProp("rate", 50);
  private static final int DURATION = LoadConfig.intProp("duration", 180);
  private static final List<String> PLAYERS = Fixtures.playerIds(2_000);

  private final ChainBuilder read =
      exec(
          grpc("GetTopN(10)")
              .rpc(LeaderboardServiceGrpc.getGetTopNMethod())
              .payload(
                  GetTopNRequest.newBuilder()
                      .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                      .setN(10)
                      .build())
              .check(statusCode().is(Status.Code.OK)));

  private final ChainBuilder write =
      exec(
          grpc("SubmitScore")
              .rpc(LeaderboardServiceGrpc.getSubmitScoreMethod())
              .payload(
                  perUser(
                      session -> {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        return SubmitScoreRequest.newBuilder()
                            .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                            .setPlayerId(PLAYERS.get(random.nextInt(PLAYERS.size())))
                            .setScore(random.nextInt(20_000))
                            .setIdempotencyKey("rollout-" + UUID.randomUUID())
                            .build();
                      }))
              .check(statusCode().is(Status.Code.OK)));

  private final ScenarioBuilder traffic =
      scenario("Traffic during rollout")
          .randomSwitch()
          .on(Choice.withWeight(80.0, read), Choice.withWeight(20.0, write));

  {
    setUp(traffic.injectOpen(constantUsersPerSec(RATE).during(Duration.ofSeconds(DURATION))))
        .protocols(LoadConfig.protocol())
        .assertions(global().failedRequests().count().is(0L));
  }
}
