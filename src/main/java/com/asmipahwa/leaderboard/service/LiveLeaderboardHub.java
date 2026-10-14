package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.BoardKey;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fans out "this leaderboard changed" signals to live subscribers (gRPC streams, WebSockets).
 *
 * <p>Changes are debounced: a write only marks the board dirty, and a timer pushes dirty boards
 * every 250 ms. A board receiving 5,000 writes a second still costs subscribers at most 4 pushes a
 * second.
 *
 * <p>Limitation: signals are in-process, so a subscriber only hears about writes that landed on the
 * same pod. Across pods this would use Redis pub/sub or a Kafka topic.
 */
@Component
public class LiveLeaderboardHub {

  /** Handle returned by {@link #subscribe}; closing it stops the notifications. */
  @FunctionalInterface
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  private static final Logger log = LoggerFactory.getLogger(LiveLeaderboardHub.class);

  private final Map<BoardKey, Set<Runnable>> listeners = new ConcurrentHashMap<>();
  private final Set<BoardKey> dirty = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public Subscription subscribe(BoardKey key, Runnable onChange) {
    listeners.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(onChange);
    return () ->
        listeners.computeIfPresent(
            key,
            (k, set) -> {
              set.remove(onChange);
              return set.isEmpty() ? null : set;
            });
  }

  public void markChanged(BoardKey key) {
    if (listeners.containsKey(key)) {
      dirty.add(key);
    }
  }

  public int subscriberCount(BoardKey key) {
    Set<Runnable> set = listeners.get(key);
    return set == null ? 0 : set.size();
  }

  @Scheduled(fixedDelayString = "${leaderboard.live.push-interval-ms:250}")
  void flush() {
    for (BoardKey key : dirty) {
      dirty.remove(key);
      Set<Runnable> set = listeners.get(key);
      if (set == null) {
        continue;
      }
      for (Runnable listener : set) {
        executor.execute(
            () -> {
              try {
                listener.run();
              } catch (RuntimeException e) {
                log.warn("Live leaderboard push failed for {}", key, e);
              }
            });
      }
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
