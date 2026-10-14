package com.asmipahwa.leaderboard.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Loads the Lua scripts. Spring sends them with {@code EVALSHA}, so Redis receives only a short
 * hash per call once a script is cached.
 */
@Configuration
public class RedisConfig {

  @Bean
  @SuppressWarnings("rawtypes")
  public RedisScript<List> recordScoreScript() {
    return RedisScript.of(new ClassPathResource("scripts/record_score.lua"), List.class);
  }

  @Bean
  @SuppressWarnings("rawtypes")
  public RedisScript<List> topNScript() {
    return RedisScript.of(new ClassPathResource("scripts/top_n.lua"), List.class);
  }

  @Bean
  @SuppressWarnings("rawtypes")
  public RedisScript<List> playerRankScript() {
    return RedisScript.of(new ClassPathResource("scripts/player_rank.lua"), List.class);
  }

  @Bean
  public RedisScript<Long> releaseLockScript() {
    return RedisScript.of(new ClassPathResource("scripts/release_lock.lua"), Long.class);
  }
}
