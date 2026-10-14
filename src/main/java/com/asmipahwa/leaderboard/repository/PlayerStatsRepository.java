package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.PlayerStatsEntity;
import com.asmipahwa.leaderboard.domain.PlayerStatsId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to {@link PlayerStatsEntity}. Custom queries are added as features need them.
 */
public interface PlayerStatsRepository extends JpaRepository<PlayerStatsEntity, PlayerStatsId> {}
