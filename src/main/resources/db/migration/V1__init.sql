-- Postgres is the source of truth for every score and stat.
-- Redis only holds a rebuildable ranking cache on top of these tables.

CREATE TABLE games (
  game_id        TEXT PRIMARY KEY,
  name           TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE leaderboards (
  leaderboard_id TEXT NOT NULL,
  game_id        TEXT NOT NULL REFERENCES games(game_id),
  name           TEXT NOT NULL,
  sort_order     TEXT NOT NULL CHECK (sort_order IN ('DESC','ASC')),
  reset_period   TEXT NOT NULL CHECK (reset_period IN ('NONE','DAILY','WEEKLY','SEASON')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (game_id, leaderboard_id)
);

CREATE TABLE players (
  player_id      UUID PRIMARY KEY,
  display_name   TEXT NOT NULL,
  region         TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE friendships (
  player_a UUID NOT NULL REFERENCES players(player_id),
  player_b UUID NOT NULL REFERENCES players(player_id),
  PRIMARY KEY (player_a, player_b),
  CHECK (player_a < player_b)
);

CREATE TABLE scores (
  game_id        TEXT NOT NULL,
  leaderboard_id TEXT NOT NULL,
  period_key     TEXT NOT NULL,          -- e.g. 'all', '2026-W42', '2026-10-14', 'S1'
  player_id      UUID NOT NULL REFERENCES players(player_id),
  best_score     BIGINT NOT NULL,
  achieved_at    TIMESTAMPTZ NOT NULL,
  version        BIGINT NOT NULL DEFAULT 0,   -- optimistic locking
  PRIMARY KEY (game_id, leaderboard_id, period_key, player_id),
  FOREIGN KEY (game_id, leaderboard_id) REFERENCES leaderboards(game_id, leaderboard_id)
);
CREATE INDEX idx_scores_rank ON scores (game_id, leaderboard_id, period_key, best_score DESC);

CREATE TABLE score_events (
  event_id        BIGSERIAL PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  game_id         TEXT NOT NULL,
  leaderboard_id  TEXT NOT NULL,
  player_id       UUID NOT NULL,
  score           BIGINT NOT NULL,
  accepted        BOOLEAN NOT NULL,      -- true if it improved the best score
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_player ON score_events (player_id, received_at DESC);

CREATE TABLE player_stats (
  game_id        TEXT NOT NULL REFERENCES games(game_id),
  player_id      UUID NOT NULL REFERENCES players(player_id),
  matches_played BIGINT NOT NULL DEFAULT 0,
  total_score    BIGINT NOT NULL DEFAULT 0,
  best_score     BIGINT NOT NULL DEFAULT 0,
  wins           BIGINT NOT NULL DEFAULT 0,
  last_played_at TIMESTAMPTZ,
  version        BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (game_id, player_id)
);
