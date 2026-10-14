package com.asmipahwa.leaderboard.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite primary key of {@link Friendship}. */
public record FriendshipId(UUID playerA, UUID playerB) implements Serializable {}
