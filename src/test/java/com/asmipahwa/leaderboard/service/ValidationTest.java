package com.asmipahwa.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.asmipahwa.leaderboard.error.InvalidRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"FC-Mobile", "has space", "-leading-dash", "x/y"})
  void rejectsMalformedIds(String id) {
    assertThatThrownBy(() -> Validation.id(id, "game_id"))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("game_id");
  }

  @Test
  void acceptsLowercaseKebabIds() {
    assertThat(Validation.id("season-1-high-score", "leaderboard_id"))
        .isEqualTo("season-1-high-score");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "nope", "12345678-1234-1234-1234-1234567890123"})
  void rejectsMalformedPlayerIds(String id) {
    assertThatThrownBy(() -> Validation.playerId(id)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void acceptsUuidPlayerIds() {
    UUID id = UUID.randomUUID();
    assertThat(Validation.playerId(id.toString())).isEqualTo(id);
  }

  @Test
  void scoresMustBeNonNegativeAndExactInRedis() {
    assertThat(Validation.score(0)).isZero();
    assertThat(Validation.score(Validation.MAX_SCORE)).isEqualTo(1L << 53);
    assertThatThrownBy(() -> Validation.score(-1)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Validation.score(Validation.MAX_SCORE + 1))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void pageSizeDefaultsAndCaps() {
    assertThat(Validation.pageSize(0, 10)).isEqualTo(10);
    assertThat(Validation.pageSize(1000, 10)).isEqualTo(1000);
    assertThatThrownBy(() -> Validation.pageSize(1001, 10))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Validation.pageSize(-1, 10))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void aroundDefaultsToTwo() {
    assertThat(Validation.around(0)).isEqualTo(2);
    assertThatThrownBy(() -> Validation.around(51)).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void idempotencyKeyIsRequired() {
    assertThatThrownBy(() -> Validation.idempotencyKey(" "))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Validation.idempotencyKey("x".repeat(129)))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void periodKeyIsOptional() {
    assertThat(Validation.period(null)).isEmpty();
    assertThat(Validation.period("2026-W42")).isEqualTo("2026-W42");
    assertThatThrownBy(() -> Validation.period("bad key!"))
        .isInstanceOf(InvalidRequestException.class);
  }
}
