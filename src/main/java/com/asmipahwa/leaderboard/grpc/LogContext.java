package com.asmipahwa.leaderboard.grpc;

import org.slf4j.MDC;

/**
 * Adds request ids (never names or other personal data) to every log line written while handling a
 * call, so logs can be filtered by game or player during an investigation.
 */
final class LogContext implements AutoCloseable {

  private LogContext(String gameId, String playerId) {
    MDC.put("gameId", gameId);
    if (playerId != null && !playerId.isEmpty()) {
      MDC.put("playerId", playerId);
    }
  }

  static LogContext of(String gameId, String playerId) {
    return new LogContext(gameId, playerId);
  }

  @Override
  public void close() {
    MDC.remove("gameId");
    MDC.remove("playerId");
  }
}
