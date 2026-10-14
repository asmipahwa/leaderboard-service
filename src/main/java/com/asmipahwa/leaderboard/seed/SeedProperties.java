package com.asmipahwa.leaderboard.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs for {@link DataSeeder}. Defaults match the benchmark setup: 100k players and 500k score
 * events. Tests use much smaller numbers.
 *
 * @param players number of players to create; every one of them gets a score on the benchmark board
 * @param events total score events to generate across all boards
 * @param avgFriends average friends per player
 * @param randomSeed fixed seed so every run produces the same data
 * @param reset wipe existing data first instead of skipping when data already exists
 * @param exitWhenDone stop the JVM after seeding, so a Kubernetes Job / compose run completes
 *     (scheduled-task threads would otherwise keep it alive)
 */
@ConfigurationProperties("seed")
public record SeedProperties(
    @DefaultValue("100000") int players,
    @DefaultValue("500000") int events,
    @DefaultValue("10") int avgFriends,
    @DefaultValue("42") long randomSeed,
    @DefaultValue("false") boolean reset,
    @DefaultValue("true") boolean exitWhenDone) {}
