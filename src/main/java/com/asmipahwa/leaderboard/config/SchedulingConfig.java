package com.asmipahwa.leaderboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs: live leaderboard pushes and the cache reconciler. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
