package com.asmipahwa.leaderboard.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite primary key of {@link PlayerStatsEntity}. */
public record PlayerStatsId(String gameId, UUID playerId) implements Serializable {}
