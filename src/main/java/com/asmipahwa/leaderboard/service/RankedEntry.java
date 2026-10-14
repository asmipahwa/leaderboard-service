package com.asmipahwa.leaderboard.service;

/** One row of a leaderboard as shown to players. Shared by gRPC and REST responses. */
public record RankedEntry(long rank, String playerId, String displayName, long score) {}
