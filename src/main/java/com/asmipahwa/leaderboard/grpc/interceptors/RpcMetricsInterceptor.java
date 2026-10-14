package com.asmipahwa.leaderboard.grpc.interceptors;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Records latency and outcome of every RPC as {@code leaderboard_query_seconds{rpc,status}} (with a
 * percentile histogram, so Prometheus can compute p95 across pods) and logs calls that are slower
 * than a threshold, or fail with a server-side error.
 */
@GrpcGlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RpcMetricsInterceptor implements ServerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(RpcMetricsInterceptor.class);

  private final MeterRegistry meters;
  private final long slowCallNanos;

  public RpcMetricsInterceptor(
      MeterRegistry meters, @Value("${leaderboard.grpc.slow-call-ms:50}") long slowCallMillis) {
    this.meters = meters;
    this.slowCallNanos = TimeUnit.MILLISECONDS.toNanos(slowCallMillis);
  }

  @Override
  public <Q, R> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
    String rpc = call.getMethodDescriptor().getBareMethodName();
    long started = System.nanoTime();
    ServerCall<Q, R> timed =
        new SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            long elapsed = System.nanoTime() - started;
            timer(rpc, status.getCode().name()).record(elapsed, TimeUnit.NANOSECONDS);
            if (elapsed > slowCallNanos || isServerError(status)) {
              try (var ignored = MDC.putCloseable("rpc", rpc)) {
                log.warn(
                    "rpc={} status={} latency_ms={} description={}",
                    rpc,
                    status.getCode(),
                    TimeUnit.NANOSECONDS.toMillis(elapsed),
                    status.getDescription());
              }
            }
            super.close(status, trailers);
          }
        };
    return next.startCall(timed, headers);
  }

  private Timer timer(String rpc, String status) {
    return Timer.builder("leaderboard.query")
        .description("Server-side latency of each gRPC call")
        .tags("rpc", rpc, "status", status)
        .publishPercentileHistogram()
        .serviceLevelObjectives(Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(50))
        .register(meters);
  }

  private static boolean isServerError(Status status) {
    return switch (status.getCode()) {
      case INTERNAL, UNAVAILABLE, UNKNOWN, DATA_LOSS, DEADLINE_EXCEEDED -> true;
      default -> false;
    };
  }
}
