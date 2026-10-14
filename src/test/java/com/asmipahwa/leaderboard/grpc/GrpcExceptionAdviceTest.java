package com.asmipahwa.leaderboard.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.error.ConflictException;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.error.UnavailableException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class GrpcExceptionAdviceTest {

  private final GrpcExceptionAdvice advice = new GrpcExceptionAdvice();

  @Test
  void invalidRequestIsInvalidArgument() {
    Status status = advice.invalid(new InvalidRequestException("n too big"));
    assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status.getDescription()).isEqualTo("n too big");
  }

  @Test
  void notFoundIsNotFound() {
    assertThat(advice.notFound(new NotFoundException("x")).getCode())
        .isEqualTo(Status.Code.NOT_FOUND);
  }

  @Test
  void dependencyFailuresAreUnavailableWithoutLeakingDetails() {
    Status status = advice.unavailable(new RedisConnectionFailureException("redis:6379 refused"));
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status.getDescription()).doesNotContain("6379");
    assertThat(advice.unavailable(new UnavailableException("rebuilding")).getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  void exhaustedLockRetriesAreAborted() {
    assertThat(advice.aborted(new ConflictException("busy", null)).getCode())
        .isEqualTo(Status.Code.ABORTED);
  }

  @Test
  void anythingElseIsInternal() {
    assertThat(advice.internal(new IllegalStateException("bug")).getCode())
        .isEqualTo(Status.Code.INTERNAL);
  }
}
