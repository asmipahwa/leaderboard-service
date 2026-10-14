package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.Score;
import com.asmipahwa.leaderboard.domain.ScoreId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link Score}. Custom queries are added as features need them. */
public interface ScoreRepository extends JpaRepository<Score, ScoreId> {}
