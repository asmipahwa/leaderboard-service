package com.asmipahwa.leaderboard.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Display names for leaderboard rows, from the Redis hash {@code player:names}.
 *
 * <p>A top-100 response needs 100 names. Joining {@code players} in SQL would put Postgres back on
 * the hot path, so names live in one Redis hash and are fetched with a single {@code HMGET}. Misses
 * (a new player, or a flushed Redis) fall back to Postgres and are written back.
 */
@Component
public class PlayerNameCache {

  static final String KEY = "player:names";

  private final StringRedisTemplate redis;
  private final NamedParameterJdbcTemplate jdbc;
  private final RedisGuard guard;

  public PlayerNameCache(
      StringRedisTemplate redis, NamedParameterJdbcTemplate jdbc, RedisGuard guard) {
    this.redis = redis;
    this.jdbc = jdbc;
    this.guard = guard;
  }

  /** Names for the given ids. Ids that don't exist in Postgres either are left out. */
  public Map<String, String> namesOf(List<String> playerIds) {
    if (playerIds.isEmpty()) {
      return Map.of();
    }
    List<Object> values =
        guard.call(
            "names_get",
            () -> redis.opsForHash().multiGet(KEY, new ArrayList<Object>(playerIds)),
            () -> Collections.nCopies(playerIds.size(), null));
    Map<String, String> names = new HashMap<>(playerIds.size() * 2);
    List<UUID> missing = new ArrayList<>();
    for (int i = 0; i < playerIds.size(); i++) {
      Object value = values.get(i);
      if (value != null) {
        names.put(playerIds.get(i), (String) value);
      } else {
        missing.add(UUID.fromString(playerIds.get(i)));
      }
    }
    if (!missing.isEmpty()) {
      Map<String, String> loaded = loadFromDatabase(missing);
      names.putAll(loaded);
      putAll(loaded);
    }
    return names;
  }

  /** Name of one player, or null if the player does not exist. */
  public String nameOf(UUID playerId) {
    return namesOf(List.of(playerId.toString())).get(playerId.toString());
  }

  public void putAll(Map<String, String> names) {
    if (!names.isEmpty()) {
      guard.run("names_put", () -> redis.opsForHash().putAll(KEY, names));
    }
  }

  private Map<String, String> loadFromDatabase(List<UUID> ids) {
    Map<String, String> loaded = new HashMap<>();
    jdbc.query(
        "SELECT player_id, display_name FROM players WHERE player_id IN (:ids)",
        Map.of("ids", ids),
        rs -> {
          loaded.put(rs.getString("player_id"), rs.getString("display_name"));
        });
    return loaded;
  }
}
