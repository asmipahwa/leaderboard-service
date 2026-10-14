package com.asmipahwa.leaderboard.load;

import static com.asmipahwa.leaderboard.load.LoadConfig.perUser;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.extract;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.grpc;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.statusCode;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Choice;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.grpc.Status;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Backs the resume claim "top-100 leaderboard queries in under 10 ms at p95".
 *
 * <p>90% GetTopN(n=100) and 10% GetPlayerRank (change with -PrankShare) against the seeded
 * 100k-player board, ramping to {@code rate} requests/s (default 300) and holding for {@code
 * duration} seconds (default 120), after a warm-up that is not measured.
 */
public class ReadHeavySimulation extends Simulation {

  private static final int RATE = LoadConfig.intProp("rate", 300);
  private static final int DURATION = LoadConfig.intProp("duration", 120);

  /** Percent of requests that are GetPlayerRank (the rest are GetTopN(100)). */
  private static final int RANK_SHARE = LoadConfig.intProp("rankShare", 10);

  private static final List<String> PLAYERS = Fixtures.playerIds(10_000);

  private static final GetTopNRequest TOP_100 =
      GetTopNRequest.newBuilder().setLeaderboard(LoadConfig.BENCHMARK_BOARD).setN(100).build();

  private final Iterator<Map<String, Object>> players =
      Stream.generate(
              () ->
                  Map.<String, Object>of(
                      "playerId", PLAYERS.get(ThreadLocalRandom.current().nextInt(PLAYERS.size()))))
          .iterator();

  private final ChainBuilder topN =
      exec(
          grpc("GetTopN(100)")
              .rpc(LeaderboardServiceGrpc.getGetTopNMethod())
              .payload(TOP_100)
              .check(extract(GetTopNResponse::getEntriesCount, Integer.class).is(100)));

  private final ChainBuilder playerRank =
      feed(players)
          .exec(
              grpc("GetPlayerRank")
                  .rpc(LeaderboardServiceGrpc.getGetPlayerRankMethod())
                  .payload(
                      perUser(
                          session ->
                              GetPlayerRankRequest.newBuilder()
                                  .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                                  .setPlayerId(session.getString("playerId"))
                                  .setAround(2)
                                  .build()))
                  .check(statusCode().is(Status.Code.OK)));

  private final ScenarioBuilder readers =
      scenario("Leaderboard readers")
          .randomSwitch()
          .on(
              Choice.withWeight(100.0 - RANK_SHARE, topN),
              Choice.withWeight(RANK_SHARE, playerRank));

  {
    setUp(
            readers.injectOpen(
                rampUsersPerSec(10).to(RATE).during(Duration.ofSeconds(30)),
                constantUsersPerSec(RATE).during(Duration.ofSeconds(DURATION))))
        .protocols(LoadConfig.protocol())
        .assertions(
            details("GetTopN(100)").responseTime().percentile(95.0).lt(10),
            global().failedRequests().percent().lt(0.1));
  }

  @Override
  public void before() {
    try {
      Fixtures.warmUp(PLAYERS, LoadConfig.intProp("warmup", 20_000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
