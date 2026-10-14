package com.asmipahwa.leaderboard.load;

import static com.github.phisgr.gatling.kt.grpc.GrpcDsl.grpc;

import com.asmipahwa.leaderboard.grpc.v1.LeaderboardRef;
import com.github.phisgr.gatling.kt.grpc.StaticGrpcProtocol;
import io.gatling.javaapi.core.Session;
import io.grpc.ManagedChannelBuilder;
import java.util.function.Function;

/**
 * Settings shared by every simulation, overridable with -D system properties so the same code
 * can target docker-compose, kind or anything else.
 */
public final class LoadConfig {

  public static final String HOST = System.getProperty("grpc.host", "localhost");
  public static final int PORT = Integer.getInteger("grpc.port", 9090);
  public static final String GAME = System.getProperty("game", "fc-mobile");
  public static final String BOARD = System.getProperty("board", "season-1-high-score");

  /** The seeded 100k-player benchmark board (current period). */
  /** load-test/build: where run files and evidence go (same path for Gatling and the verifier). */
  public static final java.nio.file.Path LOAD_DIR =
      java.nio.file.Path.of(System.getProperty("load.dir", "load-test/build"));

  public static final LeaderboardRef BENCHMARK_BOARD =
      LeaderboardRef.newBuilder().setGameId(GAME).setLeaderboardId(BOARD).build();

  private LoadConfig() {}

  /**
   * gRPC protocol: one long-lived HTTP/2 channel shared by all virtual users, which is how gRPC
   * clients and game servers talk to a backend (many concurrent calls multiplexed over a
   * connection) instead of opening a new TCP connection per request.
   */
  public static StaticGrpcProtocol protocol() {
    return grpc(ManagedChannelBuilder.forAddress(HOST, PORT).usePlaintext()).shareChannel();
  }

  /** Builds a request per virtual user (typed so Java picks the right DSL overload). */
  public static <T> Function<Session, T> perUser(Function<Session, T> requestFactory) {
    return requestFactory;
  }

  public static int intProp(String name, int defaultValue) {
    return Integer.getInteger(name, defaultValue);
  }
}
