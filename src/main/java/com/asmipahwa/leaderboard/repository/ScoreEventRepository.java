package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.ScoreEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link ScoreEvent}. Custom queries are added as features need them. */
public interface ScoreEventRepository extends JpaRepository<ScoreEvent, Long> {}
