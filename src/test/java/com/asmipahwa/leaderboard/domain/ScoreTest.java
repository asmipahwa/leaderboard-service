package com.asmipahwa.leaderboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoreTest {

  private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);

  private static Score scoreOf(long best) {
    return new Score(new ScoreId("g", "lb", "all", UUID.randomUUID()), best, T0);
  }

  @Test
  void higherScoreImprovesDescendingBoard() {
    Score score = scoreOf(100);
    assertThat(score.offer(150, SortOrder.DESC, T1)).isTrue();
    assertThat(score.getBestScore()).isEqualTo(150);
    assertThat(score.getAchievedAt()).isEqualTo(T1);
  }

  @Test
  void equalOrLowerScoreDoesNotImproveDescendingBoard() {
    Score score = scoreOf(100);
    assertThat(score.offer(100, SortOrder.DESC, T1)).isFalse();
    assertThat(score.offer(50, SortOrder.DESC, T1)).isFalse();
    assertThat(score.getBestScore()).isEqualTo(100);
    assertThat(score.getAchievedAt()).isEqualTo(T0);
  }

  @Test
  void lowerTimeImprovesAscendingBoard() {
    Score lap = scoreOf(62_000);
    assertThat(lap.offer(70_000, SortOrder.ASC, T1)).isFalse();
    assertThat(lap.offer(58_500, SortOrder.ASC, T1)).isTrue();
    assertThat(lap.getBestScore()).isEqualTo(58_500);
  }

  @Test
  void friendshipIsStoredInCanonicalOrder() {
    UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001");
    UUID high = UUID.fromString("ffffffff-0000-4000-8000-000000000001");
    Friendship one = Friendship.between(high, low);
    Friendship two = Friendship.between(low, high);
    assertThat(one.getPlayerA()).isEqualTo(low).isEqualTo(two.getPlayerA());
    assertThat(one.getPlayerB()).isEqualTo(high);
  }

  @Test
  void playerCannotBefriendThemselves() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> Friendship.between(id, id))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
