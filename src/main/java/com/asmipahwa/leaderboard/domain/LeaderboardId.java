package com.asmipahwa.leaderboard.domain;

import java.io.Serializable;

/** Composite primary key of {@link Leaderboard}: a board id is only unique within its game. */
public record LeaderboardId(String gameId, String leaderboardId) implements Serializable {}
