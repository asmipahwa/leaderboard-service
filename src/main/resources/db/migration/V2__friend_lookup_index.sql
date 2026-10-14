-- Friendships are stored once as (player_a < player_b). The primary key already serves
-- "WHERE player_a = ?"; this index serves the other half of a friends lookup, "WHERE player_b = ?".
CREATE INDEX idx_friendships_player_b ON friendships (player_b);
