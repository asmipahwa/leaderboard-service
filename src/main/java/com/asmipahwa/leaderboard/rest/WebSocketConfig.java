package com.asmipahwa.leaderboard.rest;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the live leaderboard WebSocket ({@code /ws/leaderboards/{game}/{board}}) and serves the
 * demo page at {@code /demo}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer, WebMvcConfigurer {

  private final LiveLeaderboardPublisher publisher;

  public WebSocketConfig(LiveLeaderboardPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(publisher, "/ws/leaderboards/*/*").setAllowedOriginPatterns("*");
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/demo", "/demo/index.html");
  }
}
