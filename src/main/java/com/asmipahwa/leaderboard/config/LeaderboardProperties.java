package com.asmipahwa.leaderboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Service-level settings under the {@code leaderboard.*} prefix, bound once at startup so the rest
 * of the code gets typed values instead of string lookups.
 *
 * @param currentSeason period key used by SEASON boards, e.g. {@code S1}
 */
@ConfigurationProperties("leaderboard")
public record LeaderboardProperties(@DefaultValue("S1") String currentSeason) {}
