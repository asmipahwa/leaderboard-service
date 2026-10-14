package com.asmipahwa.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.asmipahwa.leaderboard.cache.RedisGuard;
import com.asmipahwa.leaderboard.service.ScoreSubmissionService.SubmitResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class IdempotencyServiceTest {

  private final IdempotencyService service =
      new IdempotencyService(
          mock(StringRedisTemplate.class), new RedisGuard(new SimpleMeterRegistry(), 50, 10, 5));

  @Test
  void storedResultRoundTripsExactly() {
    SubmitResult original = new SubmitResult(true, 12_500, 3, 41, List.of("Rising Star", "Pro"));
    assertThat(service.decode(service.encode(original))).isEqualTo(original);
  }

  @Test
  void resultWithoutAchievementsRoundTrips() {
    SubmitResult original = new SubmitResult(false, 900, 7, 7, List.of());
    assertThat(service.decode(service.encode(original))).isEqualTo(original);
  }

  @Test
  void keysAreNamespaced() {
    assertThat(IdempotencyService.key("abc")).isEqualTo("idem:abc");
  }
}
