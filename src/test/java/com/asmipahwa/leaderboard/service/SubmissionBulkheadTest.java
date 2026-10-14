package com.asmipahwa.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.asmipahwa.leaderboard.error.UnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SubmissionBulkheadTest {

  @Test
  void neverRunsMoreThanTheLimitAtOnce() throws Exception {
    SubmissionBulkhead bulkhead = new SubmissionBulkhead(new SimpleMeterRegistry(), 3, 5_000);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxSeen = new AtomicInteger();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        executor.submit(
            () ->
                bulkhead.run(
                    () -> {
                      maxSeen.accumulateAndGet(running.incrementAndGet(), Math::max);
                      sleep(5);
                      running.decrementAndGet();
                      return null;
                    }));
      }
    }
    assertThat(maxSeen.get()).isEqualTo(3);
  }

  @Test
  void waitingTooLongIsUnavailable() throws Exception {
    SubmissionBulkhead bulkhead = new SubmissionBulkhead(new SimpleMeterRegistry(), 1, 50);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(
          () ->
              bulkhead.run(
                  () -> {
                    holding.countDown();
                    await(release);
                    return null;
                  }));
      holding.await();
      assertThatThrownBy(() -> bulkhead.run(() -> "late")).isInstanceOf(UnavailableException.class);
      release.countDown();
    }
    assertThat(bulkhead.run(() -> "ok")).isEqualTo("ok");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
