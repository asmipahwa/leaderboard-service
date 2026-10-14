-- Achievements: simple per-game threshold rules ("score 10,000 points"), unlocked at most once.
CREATE TABLE achievements (
  achievement_id BIGSERIAL PRIMARY KEY,
  game_id        TEXT NOT NULL REFERENCES games(game_id),
  name           TEXT NOT NULL,
  threshold      BIGINT NOT NULL,
  UNIQUE (game_id, name)
);

CREATE TABLE player_achievements (
  player_id      UUID NOT NULL REFERENCES players(player_id),
  achievement_id BIGINT NOT NULL REFERENCES achievements(achievement_id),
  unlocked_at    TIMESTAMPTZ NOT NULL,
  -- The primary key is what makes "unlock once" safe under concurrency (INSERT ... ON CONFLICT).
  PRIMARY KEY (player_id, achievement_id)
);
