package com.asmipahwa.leaderboard.error;

/**
 * A write kept losing optimistic-lock races and we gave up after the retry budget. Maps to gRPC
 * {@code ABORTED} / HTTP 409: the request was valid and nothing was written, so the client can
 * safely retry it with the same idempotency key.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
