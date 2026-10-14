package com.asmipahwa.leaderboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SortOrderTest {

  @Test
  void descendingStoresTheScoreAsIs() {
    assertThat(SortOrder.DESC.toRankingScore(1234)).isEqualTo(1234.0);
    assertThat(SortOrder.DESC.fromRankingScore(1234.0)).isEqualTo(1234);
  }

  @Test
  void ascendingStoresTheNegatedScoreSoHigherIsAlwaysBetter() {
    double fast = SortOrder.ASC.toRankingScore(58_500);
    double slow = SortOrder.ASC.toRankingScore(62_000);
    assertThat(fast).isGreaterThan(slow);
    assertThat(SortOrder.ASC.fromRankingScore(fast)).isEqualTo(58_500);
  }

  @Test
  void largestAllowedScoreRoundTripsExactly() {
    long max = 1L << 53;
    assertThat(SortOrder.DESC.fromRankingScore(SortOrder.DESC.toRankingScore(max))).isEqualTo(max);
    assertThat(SortOrder.ASC.fromRankingScore(SortOrder.ASC.toRankingScore(max))).isEqualTo(max);
  }
}
