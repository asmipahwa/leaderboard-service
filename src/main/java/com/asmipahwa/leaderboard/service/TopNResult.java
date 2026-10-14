package com.asmipahwa.leaderboard.service;

import java.time.Instant;
import java.util.List;

/** A page of a leaderboard plus the total number of ranked players. */
public record TopNResult(List<RankedEntry> entries, long totalPlayers, Instant asOf) {}
