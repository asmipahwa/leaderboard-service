package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.error.UnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how many score submissions run at the same time (a "bulkhead").
 *
 * <p>When thousands of matches end at once, letting every request in immediately doesn't make
 * anything faster: Postgres only runs 20 transactions at a time (the connection pool), so the rest
 * would wait anyway. Meanwhile they would all hit Redis and the CPU at the same instant, which is
 * exactly when Redis calls start timing out. Requests over the limit wait here in a fair
 * (first-come, first-served) queue instead. If one waits longer than the queue timeout it fails
 * with UNAVAILABLE, and the client retries safely because of its idempotency key.
 */
@Component
public class SubmissionBulkhead {

  private final Semaphore permits;
  private final int maxConcurrent;
  private final long queueTimeoutMillis;
  private final Counter rejected;

  public SubmissionBulkhead(
      MeterRegistry meters,
      @Value("${leaderboard.submit.max-concurrent:64}") int maxConcurrent,
      @Value("${leaderboard.submit.queue-timeout-ms:9000}") long queueTimeoutMillis) {
    this.permits = new Semaphore(maxConcurrent, true);
    this.maxConcurrent = maxConcurrent;
    this.queueTimeoutMillis = queueTimeoutMillis;
    this.rejected =
        Counter.builder("leaderboard.submit.rejected")
            .description("Submissions that waited too long for a slot and returned UNAVAILABLE")
            .register(meters);
    Gauge.builder("leaderboard.submit.in.flight", this, b -> b.inFlight())
        .description("Score submissions currently running")
        .register(meters);
    Gauge.builder("leaderboard.submit.queued", permits, Semaphore::getQueueLength)
        .description("Score submissions waiting for a slot")
        .register(meters);
  }

  public <T> T run(Supplier<T> submission) {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UnavailableException("Interrupted while waiting to submit");
    }
    if (!acquired) {
      rejected.increment();
      throw new UnavailableException("Too many score submissions right now, please retry");
    }
    try {
      return submission.get();
    } finally {
      permits.release();
    }
  }

  int inFlight() {
    return maxConcurrent - permits.availablePermits();
  }
}
