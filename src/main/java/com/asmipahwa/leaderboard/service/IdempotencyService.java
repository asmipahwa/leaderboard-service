package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.RedisGuard;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Fast path for duplicate submissions: remembers each idempotency key's result in Redis for 24 h.
 *
 * <p>Mobile clients retry when a response gets lost on a bad network, so the same match result can
 * arrive several times. A duplicate found here returns the original response without touching
 * Postgres. This is only an optimisation: the {@code UNIQUE} constraint on {@code
 * score_events.idempotency_key} is the real guarantee, and it still holds if Redis is empty or
 * down.
 */
@Service
public class IdempotencyService {

  static final Duration TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;
  private final RedisGuard guard;

  public IdempotencyService(StringRedisTemplate redis, RedisGuard guard) {
    this.redis = redis;
    this.guard = guard;
  }

  /** The stored result for this key, if the request was already processed. */
  public Optional<SubmitResult> previousResult(String idempotencyKey) {
    return guard.call(
        "idempotency_get",
        () -> Optional.ofNullable(redis.opsForValue().get(key(idempotencyKey))).map(this::decode),
        Optional::empty);
  }

  /** Stores the result. {@code SET NX}: if two copies raced, the first stored result wins. */
  public void remember(String idempotencyKey, SubmitResult result) {
    guard.run(
        "idempotency_set",
        () -> redis.opsForValue().setIfAbsent(key(idempotencyKey), encode(result), TTL));
  }

  static String key(String idempotencyKey) {
    return "idem:" + idempotencyKey;
  }

  String encode(SubmitResult result) {
    return result.improved()
        + "|"
        + result.bestScore()
        + "|"
        + result.rank()
        + "|"
        + result.previousRank()
        + "|"
        + String.join(",", result.unlockedAchievements());
  }

  SubmitResult decode(String value) {
    String[] parts = value.split("\\|", -1);
    List<String> achievements = parts[4].isEmpty() ? List.of() : Arrays.asList(parts[4].split(","));
    return new SubmitResult(
        Boolean.parseBoolean(parts[0]),
        Long.parseLong(parts[1]),
        Long.parseLong(parts[2]),
        Long.parseLong(parts[3]),
        achievements);
  }
}
