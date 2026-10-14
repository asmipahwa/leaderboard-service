package com.asmipahwa.leaderboard.config;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Wires tracing into the two clients Spring Boot doesn't instrument for us, so one SubmitScore
 * trace shows every hop: gRPC server span, then JDBC spans (from datasource-micrometer), then Redis
 * command spans (from Lettuce).
 */
@Configuration
public class ObservabilityConfig {

  /** Opens a span per gRPC call; runs before every other interceptor so logs carry its traceId. */
  @GrpcGlobalServerInterceptor
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public ObservationGrpcServerInterceptor grpcServerObservation(ObservationRegistry registry) {
    return new ObservationGrpcServerInterceptor(registry);
  }

  /**
   * Keeps traces about player requests. Without this, the 4-per-second live-update timer and every
   * Prometheus scrape would each create a trace and bury the interesting ones.
   */
  @Bean
  public ObservationPredicate skipInfrastructureObservations() {
    return (name, context) -> {
      if (name.startsWith("tasks.scheduled")) {
        return false;
      }
      if (context instanceof ServerRequestObservationContext request) {
        return !request.getCarrier().getRequestURI().startsWith("/actuator");
      }
      return true;
    };
  }

  /** Makes Lettuce report each Redis command (EVALSHA, ZREVRANGE...) as a child span. */
  @Bean
  public ClientResourcesBuilderCustomizer lettuceTracing(ObservationRegistry registry) {
    return builder -> builder.tracing(new MicrometerTracing(registry, "redis"));
  }
}
