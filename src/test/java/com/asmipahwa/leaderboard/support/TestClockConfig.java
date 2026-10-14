package com.asmipahwa.leaderboard.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link MutableClock} in integration tests. */
@TestConfiguration
public class TestClockConfig {

  @Bean
  @Primary
  public MutableClock testClock() {
    return new MutableClock();
  }
}
