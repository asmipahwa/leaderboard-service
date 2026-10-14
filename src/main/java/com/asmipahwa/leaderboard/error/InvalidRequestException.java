package com.asmipahwa.leaderboard.error;

/**
 * The caller sent something we will never accept (blank id, negative score, n &gt; 1000...). Maps
 * to gRPC {@code INVALID_ARGUMENT} and HTTP 400: retrying the same request will not help.
 */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
