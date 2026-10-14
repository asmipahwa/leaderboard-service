package com.asmipahwa.leaderboard.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a UTC {@link Clock} bean. Injecting the clock (instead of calling {@code Instant.now()})
 * lets tests pin "now" to tricky moments like midnight on Dec 31.
 */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
