package com.asmipahwa.leaderboard.repository;

import com.asmipahwa.leaderboard.domain.ScoreEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link ScoreEvent}. */
public interface ScoreEventRepository extends JpaRepository<ScoreEvent, Long> {

  /** Used to detect a retried submission (same client-generated key). */
  Optional<ScoreEvent> findByIdempotencyKey(String idempotencyKey);
}
