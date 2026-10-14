package com.asmipahwa.leaderboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Game Stats &amp; Leaderboard Service: a backend that records player scores and
 * stats in PostgreSQL and serves rankings from Redis sorted sets.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LeaderboardApplication {

  public static void main(String[] args) {
    SpringApplication.run(LeaderboardApplication.class, args);
  }
}
