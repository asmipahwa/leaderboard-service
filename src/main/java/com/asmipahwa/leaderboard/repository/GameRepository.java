package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link Game}. Custom queries are added as features need them. */
public interface GameRepository extends JpaRepository<Game, String> {}
