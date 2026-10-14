package com.asmipahwa.leaderboard.load;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.grpc;
import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.statusCode;
import static com.asmipahwa.leaderboard.load.LoadConfig.perUser;

import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.SubmitScoreRequest;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backs the resume claim "2,000+ concurrent score updates with zero lost writes".
 *
 * <p>Models matches ending at the same time: {@code users} (default 2,000) virtual players start
 * at once and each submits {@code rounds} (default 3) match results, 15 s apart, so every round is
 * a burst of 2,000 simultaneous SubmitScore calls. Players are drawn from a small pool (default
 * 500) so many requests hit the same rows and optimistic locking has real conflicts to resolve.
 *
 * <p>Every request carries an idempotency key starting with this run's id. {@link WriteVerifier}
 * then checks Postgres and Redis to prove no write was lost.
 */
public class ConcurrentWriteSimulation extends Simulation {

  private static final int USERS = LoadConfig.intProp("users", 2_000);
  private static final int ROUNDS = LoadConfig.intProp("rounds", 3);
  private static final List<String> POOL = Fixtures.playerIds(LoadConfig.intProp("pool", 500));
  private static final String RUN_ID = "gatling-" + System.currentTimeMillis();

  private final ScenarioBuilder players =
      scenario("Matches ending at the same time")
          .repeat(ROUNDS, "round")
          .on(
              exec(grpc("SubmitScore")
                      .rpc(LeaderboardServiceGrpc.getSubmitScoreMethod())
                      .payload(
                          perUser(
                              session -> {
                            ThreadLocalRandom random = ThreadLocalRandom.current();
                            return SubmitScoreRequest.newBuilder()
                                .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                                .setPlayerId(POOL.get(random.nextInt(POOL.size())))
                                // Log-normal like the seed data, with an occasional new record.
                                .setScore(Math.round(Math.exp(8.5 + random.nextGaussian() * 0.7)))
                                .setWin(random.nextBoolean())
                                .setIdempotencyKey(
                                    RUN_ID + "-" + session.userId() + "-" + session.getInt("round"))
                                .build();
                          }))
                      .check(statusCode().is(Status.Code.OK)))
                  .pause(Duration.ofSeconds(15)));

  {
    setUp(players.injectOpen(atOnceUsers(USERS)))
        .protocols(LoadConfig.protocol())
        .assertions(global().failedRequests().count().is(0L));
  }

  @Override
  public void before() {
    System.out.println("ConcurrentWriteSimulation run id: " + RUN_ID);
    try {
      Path file = LoadConfig.LOAD_DIR.resolve("gatling-last-write-run.txt");
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          RUN_ID
              + "\n"
              + (USERS * ROUNDS)
              + "\n"
              + WriteVerifier.optimisticLockRetries()
              + "\n");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
