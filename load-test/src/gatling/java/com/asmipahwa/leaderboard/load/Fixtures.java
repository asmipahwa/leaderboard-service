package com.asmipahwa.leaderboard.load;

import com.asmipahwa.leaderboard.grpc.v1.GetPlayerRankRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNRequest;
import com.asmipahwa.leaderboard.grpc.v1.GetTopNResponse;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardEntry;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc;
import com.asmipahwa.leaderboard.grpc.v1.LeaderboardServiceGrpc.LeaderboardServiceBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Test data and warm-up done with a plain gRPC client before Gatling starts measuring, so setup
 * traffic never shows up in the reported numbers.
 */
public final class Fixtures {

  private Fixtures() {}

  private static ManagedChannel channel() {
    return ManagedChannelBuilder.forAddress(LoadConfig.HOST, LoadConfig.PORT)
        .usePlaintext()
        .build();
  }

  /** Real player ids from the benchmark board, read in pages of 1,000. */
  public static List<String> playerIds(int count) {
    ManagedChannel channel = channel();
    try {
      LeaderboardServiceBlockingStub stub = LeaderboardServiceGrpc.newBlockingStub(channel);
      long total =
          stub.getTopN(
                  GetTopNRequest.newBuilder()
                      .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                      .setN(1)
                      .build())
              .getTotalPlayers();
      List<String> ids = new ArrayList<>(count);
      while (ids.size() < count) {
        int offset = (int) ThreadLocalRandom.current().nextLong(Math.max(1, total - 1000));
        GetTopNResponse page =
            stub.getTopN(
                GetTopNRequest.newBuilder()
                    .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                    .setN(Math.min(1000, count - ids.size()))
                    .setOffset(offset)
                    .build());
        for (LeaderboardEntry entry : page.getEntriesList()) {
          ids.add(entry.getPlayerId());
        }
      }
      System.out.printf(
          "Fixtures: loaded %d player ids from a %d-player board%n", ids.size(), total);
      return ids;
    } finally {
      channel.shutdownNow();
    }
  }

  /**
   * Warms the service's JIT, connection pools and caches with the same calls the simulation makes,
   * so we measure steady state rather than the first seconds after a deploy.
   */
  public static void warmUp(List<String> playerIds, int requests) throws InterruptedException {
    if (requests <= 0) {
      return;
    }
    long started = System.nanoTime();
    ManagedChannel channel = channel();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      LeaderboardServiceBlockingStub stub = LeaderboardServiceGrpc.newBlockingStub(channel);
      for (int i = 0; i < requests; i++) {
        int n = i;
        pool.execute(
            () -> {
              if (n % 10 == 0) {
                stub.getPlayerRank(
                    GetPlayerRankRequest.newBuilder()
                        .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                        .setPlayerId(playerIds.get(n % playerIds.size()))
                        .build());
              } else {
                stub.getTopN(
                    GetTopNRequest.newBuilder()
                        .setLeaderboard(LoadConfig.BENCHMARK_BOARD)
                        .setN(100)
                        .build());
              }
            });
      }
      pool.shutdown();
      pool.awaitTermination(5, TimeUnit.MINUTES);
    } finally {
      channel.shutdownNow();
    }
    System.out.printf(
        "Fixtures: warm-up of %d requests took %d ms%n",
        requests, (System.nanoTime() - started) / 1_000_000);
  }
}
