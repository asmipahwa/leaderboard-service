package com.asmipahwa.leaderboard.service;

import java.util.List;

/** A player's own rank with the players just above and below them ("around me"). */
public record PlayerRankResult(
    RankedEntry me, List<RankedEntry> above, List<RankedEntry> below, double percentile) {}
