package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.Leaderboard;
import com.asmipahwa.leaderboard.domain.LeaderboardId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link Leaderboard}. Custom queries are added as features need them. */
public interface LeaderboardRepository extends JpaRepository<Leaderboard, LeaderboardId> {}
