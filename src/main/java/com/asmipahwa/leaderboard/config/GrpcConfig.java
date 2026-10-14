package com.asmipahwa.leaderboard.config;

import java.util.concurrent.Executors;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs gRPC handlers on Java 21 virtual threads.
 *
 * <p>grpc-java's default executor is an unbounded cached thread pool: a burst of 2,000 simultaneous
 * submissions creates 2,000 OS threads, all fighting for CPU with the Redis client's I/O thread.
 * Virtual threads are cheap to create and park for free while waiting on Postgres or Redis, so a
 * burst no longer means thousands of OS threads. Switch off with {@code
 * leaderboard.grpc.virtual-threads=false} to compare.
 */
@Configuration
public class GrpcConfig {

  @Bean
  @ConditionalOnProperty(
      name = "leaderboard.grpc.virtual-threads",
      havingValue = "true",
      matchIfMissing = true)
  public GrpcServerConfigurer virtualThreadExecutor() {
    return serverBuilder -> serverBuilder.executor(Executors.newVirtualThreadPerTaskExecutor());
  }
}
