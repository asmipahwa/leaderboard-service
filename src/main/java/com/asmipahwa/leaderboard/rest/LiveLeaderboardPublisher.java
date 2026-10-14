package com.asmipahwa.leaderboard.rest;

import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.service.LeaderboardQueryService;
import com.asmipahwa.leaderboard.service.LeaderboardRegistry;
import com.asmipahwa.leaderboard.service.LiveLeaderboardHub;
import com.asmipahwa.leaderboard.service.LiveLeaderboardHub.Subscription;
import com.asmipahwa.leaderboard.service.ResolvedBoard;
import com.asmipahwa.leaderboard.service.Validation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Browser version of the gRPC {@code StreamTopN}: pushes the board's top 10 as JSON over a
 * WebSocket whenever it changes (at most 4 times a second, see {@link LiveLeaderboardHub}).
 */
@Component
public class LiveLeaderboardPublisher extends TextWebSocketHandler {

  static final int TOP = 10;
  private static final Logger log = LoggerFactory.getLogger(LiveLeaderboardPublisher.class);

  private final LeaderboardRegistry registry;
  private final LeaderboardQueryService queries;
  private final LiveLeaderboardHub hub;
  private final ObjectMapper json;
  private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

  public LiveLeaderboardPublisher(
      LeaderboardRegistry registry,
      LeaderboardQueryService queries,
      LiveLeaderboardHub hub,
      ObjectMapper json) {
    this.registry = registry;
    this.queries = queries;
    this.hub = hub;
    this.json = json;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    String[] parts = session.getUri().getPath().split("/");
    ResolvedBoard board;
    try {
      board =
          registry.resolve(
              Validation.id(parts[parts.length - 2], "game_id"),
              Validation.id(parts[parts.length - 1], "leaderboard_id"),
              null);
    } catch (InvalidRequestException | NotFoundException e) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason(e.getMessage()));
      return;
    }
    Runnable push = () -> send(session, board);
    subscriptions.put(session.getId(), hub.subscribe(board.key(), push));
    push.run();
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Subscription subscription = subscriptions.remove(session.getId());
    if (subscription != null) {
      subscription.close();
    }
  }

  private void send(WebSocketSession session, ResolvedBoard board) {
    var key = board.key();
    try {
      String payload =
          json.writeValueAsString(
              queries.topN(key.gameId(), key.leaderboardId(), key.periodKey(), TOP, 0));
      // A WebSocket session must not be written to from two threads at once.
      synchronized (session) {
        if (session.isOpen()) {
          session.sendMessage(new TextMessage(payload));
        }
      }
    } catch (IOException | RuntimeException e) {
      log.debug("Could not push leaderboard to {}: {}", session.getId(), e.getMessage());
    }
  }
}
