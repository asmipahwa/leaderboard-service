package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.Friendship;
import com.asmipahwa.leaderboard.domain.FriendshipId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link Friendship}. Custom queries are added as features need them. */
public interface FriendshipRepository extends JpaRepository<Friendship, FriendshipId> {}
