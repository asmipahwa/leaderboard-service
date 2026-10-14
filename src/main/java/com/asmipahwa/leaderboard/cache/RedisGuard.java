package com.asmipahwa.leaderboard.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Wraps every Redis call in a circuit breaker with a fallback, so a Redis outage makes the service
 * slower instead of down.
 *
 * <p>When Redis errors or times out often enough, the breaker "opens" and calls go straight to the
 * fallback (Postgres, or skipping the cache) without waiting on a dead connection. After a
 * cool-down it lets a few test calls through and closes again once Redis answers. Only data-access
 * failures count: a {@code NotFoundException} thrown inside a call is a normal answer, not a Redis
 * problem, and is passed through untouched.
 */
@Component
public class RedisGuard {

  private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

  private final CircuitBreaker breaker;
  private final MeterRegistry meters;

  public RedisGuard(
      MeterRegistry meters,
      @Value("${leaderboard.redis.breaker.failure-rate-percent:50}") float failureRate,
      @Value("${leaderboard.redis.breaker.minimum-calls:10}") int minimumCalls,
      @Value("${leaderboard.redis.breaker.open-seconds:5}") long openSeconds) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(Math.max(20, minimumCalls))
            .minimumNumberOfCalls(minimumCalls)
            .failureRateThreshold(failureRate)
            .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordException(e -> e instanceof DataAccessException)
            .build();
    CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
    this.breaker = registry.circuitBreaker("redis");
    this.breaker
        .getEventPublisher()
        .onStateTransition(
            event -> log.warn("Redis circuit breaker: {}", event.getStateTransition()));
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
    this.meters = meters;
  }

  /**
   * Runs {@code redisCall}; if Redis fails or the breaker is open, runs {@code fallback} instead.
   *
   * @param operation short name for the fallback metric, e.g. {@code top_n}
   */
  public <T> T call(String operation, Supplier<T> redisCall, Supplier<T> fallback) {
    try {
      return breaker.executeSupplier(redisCall);
    } catch (CallNotPermittedException | DataAccessException e) {
      meters.counter("leaderboard.redis.fallback", "operation", operation).increment();
      log.debug("Redis fallback for {}: {}", operation, e.toString());
      return fallback.get();
    }
  }

  /** Same as {@link #call} for Redis writes that can simply be skipped when Redis is down. */
  public void run(String operation, Runnable redisCall) {
    call(
        operation,
        () -> {
          redisCall.run();
          return null;
        },
        () -> null);
  }

  public CircuitBreaker.State state() {
    return breaker.getState();
  }

  /** Test hook: forget past failures. */
  public void reset() {
    breaker.reset();
  }
}
