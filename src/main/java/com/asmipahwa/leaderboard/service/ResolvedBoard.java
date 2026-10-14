package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.cache.BoardKey;
import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.domain.SortOrder;

/**
 * A leaderboard definition paired with the concrete period being read or written. Passing this
 * around (instead of three loose strings) means the period is resolved exactly once per request.
 */
public record ResolvedBoard(Leaderboard definition, BoardKey key) {

  public SortOrder sortOrder() {
    return definition.getSortOrder();
  }
}
