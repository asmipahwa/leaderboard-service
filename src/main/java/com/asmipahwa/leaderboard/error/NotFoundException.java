package com.asmipahwa.leaderboard.error;

/**
 * A game, leaderboard, player or ranking entry does not exist. Maps to gRPC {@code NOT_FOUND} and
 * HTTP 404, so clients can tell "you asked for something that isn't there" from a server fault.
 */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
