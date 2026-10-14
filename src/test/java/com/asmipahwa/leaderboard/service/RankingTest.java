package com.asmipahwa.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.asmipahwa.leaderboard.service.Ranking.Scored;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RankingTest {

  private static List<Long> ranks(List<RankedEntry> entries) {
    return entries.stream().map(RankedEntry::rank).toList();
  }

  @Test
  void tiesShareARankAndTheNextRankIsSkipped() {
    List<Scored> scores =
        List.of(
            new Scored("a", 100), new Scored("b", 90), new Scored("c", 90), new Scored("d", 80));
    assertThat(ranks(Ranking.assign(scores, 0, 1, Map.of()))).containsExactly(1L, 2L, 2L, 4L);
  }

  @Test
  void allTiedPlayersAreFirst() {
    List<Scored> scores = List.of(new Scored("a", 5), new Scored("b", 5), new Scored("c", 5));
    assertThat(ranks(Ranking.assign(scores, 0, 1, Map.of()))).containsExactly(1L, 1L, 1L);
  }

  @Test
  void pagesUseTheirOffsetForPositionalRanks() {
    // Page starting at position 10 (rank 11), no ties.
    List<Scored> scores = List.of(new Scored("a", 50), new Scored("b", 40), new Scored("c", 30));
    assertThat(ranks(Ranking.assign(scores, 10, 11, Map.of()))).containsExactly(11L, 12L, 13L);
  }

  @Test
  void pageStartingInsideATieUsesTheLookedUpFirstRank() {
    // Positions 10 and 11 are tied with position 9 (rank 9 for all three).
    List<Scored> scores = List.of(new Scored("a", 50), new Scored("b", 50), new Scored("c", 40));
    assertThat(ranks(Ranking.assign(scores, 10, 9, Map.of()))).containsExactly(9L, 9L, 13L);
  }

  @Test
  void namesAreFilledInAndMissingNamesAreBlank() {
    List<RankedEntry> entries =
        Ranking.assign(List.of(new Scored("a", 2), new Scored("b", 1)), 0, 1, Map.of("a", "Alice"));
    assertThat(entries.get(0).displayName()).isEqualTo("Alice");
    assertThat(entries.get(1).displayName()).isEmpty();
  }

  @Test
  void percentileIsShareOfPlayersBeaten() {
    assertThat(Ranking.percentile(1, 100)).isEqualTo(0.99);
    assertThat(Ranking.percentile(100, 100)).isEqualTo(0.0);
    assertThat(Ranking.percentile(1, 0)).isEqualTo(0.0);
  }
}
