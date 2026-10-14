package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.Player;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link Player}. Custom queries are added as features need them. */
public interface PlayerRepository extends JpaRepository<Player, UUID> {}
