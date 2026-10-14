package com.asmipahwa.leaderboard.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A player's friend list, cached in a Redis set {@code friends:{playerId}}.
 *
 * <p>The friend graph would belong to a separate social service in a real platform; here it lives
 * in Postgres and is cached for an hour. Lists are capped at {@link #MAX_FRIENDS} so a friends
 * leaderboard is always a small, fast in-memory sort.
 */
@Service
public class FriendsService {

  public static final int MAX_FRIENDS = 500;
  private static final Duration TTL = Duration.ofHours(1);
  // Redis can't store an empty set, so a placeholder member marks "loaded, has no friends".
  private static final String EMPTY_MARKER = "-";

  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;

  public FriendsService(StringRedisTemplate redis, JdbcTemplate jdbc) {
    this.redis = redis;
    this.jdbc = jdbc;
  }

  public List<String> friendsOf(UUID playerId) {
    String key = "friends:" + playerId;
    Set<String> cached = redis.opsForSet().members(key);
    if (cached != null && !cached.isEmpty()) {
      List<String> friends = new ArrayList<>(cached);
      friends.remove(EMPTY_MARKER);
      return friends;
    }
    List<String> friends =
        jdbc.queryForList(
            "SELECT player_b::text FROM friendships WHERE player_a = ?"
                + " UNION ALL SELECT player_a::text FROM friendships WHERE player_b = ?"
                + " LIMIT "
                + MAX_FRIENDS,
            String.class,
            playerId,
            playerId);
    List<String> members = new ArrayList<>(friends);
    members.add(EMPTY_MARKER);
    redis.opsForSet().add(key, members.toArray(String[]::new));
    redis.expire(key, TTL);
    return friends;
  }
}
