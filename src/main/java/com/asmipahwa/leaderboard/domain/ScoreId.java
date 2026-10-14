package com.asmipahwa.leaderboard.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite primary key of {@link Score}: one best score per player per board period. */
public record ScoreId(String gameId, String leaderboardId, String periodKey, UUID playerId)
    implements Serializable {}
