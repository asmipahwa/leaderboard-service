package com.asmipahwa.leaderboard.error;

/**
 * A dependency is temporarily unavailable (for example a leaderboard is mid-rebuild). Maps to gRPC
 * {@code UNAVAILABLE} / HTTP 503, which tells well-behaved clients to back off and retry.
 */
public class UnavailableException extends RuntimeException {

  public UnavailableException(String message) {
    super(message);
  }
}
