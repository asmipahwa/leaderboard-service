package com.asmipahwa.leaderboard.support;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests: real Postgres 16 and Redis 7 in Docker via Testcontainers.
 *
 * <p>The containers are started once per JVM (the "singleton container" pattern) and shared by
 * every IT class, which keeps the suite fast while still testing against the real databases.
 *
 * <p>The gRPC server runs in-process (no network port), and every test class shares one Spring
 * context, so the in-process server name is registered only once.
 */
@SpringBootTest(
    properties = {
      "grpc.server.in-process-name=" + IntegrationTestSupport.GRPC_NAME,
      "grpc.server.port=-1"
    })
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

  static final String GRPC_NAME = "leaderboard-it";

  /** Channel to the in-process gRPC server, shared by all tests. */
  protected static final ManagedChannel CHANNEL =
      InProcessChannelBuilder.forName(GRPC_NAME).directExecutor().build();

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("leaderboard")
          .withUsername("leaderboard")
          .withPassword("leaderboard");

  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }
}
