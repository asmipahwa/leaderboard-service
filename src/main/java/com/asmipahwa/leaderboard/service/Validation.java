package com.asmipahwa.leaderboard.service;

import com.asmipahwa.leaderboard.error.InvalidRequestException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Input checks shared by the gRPC and REST layers. Validating inside the service (not only at the
 * edge) means every entry point gets the same rules and the same error messages.
 */
public final class Validation {

  /** Max entries per page. Bigger pages hurt tail latency for everyone sharing the Redis node. */
  public static final int MAX_PAGE = 1000;

  /**
   * Largest score we accept: 2^53. Redis stores sorted-set scores as doubles, which represent every
   * integer exactly only up to 2^53.
   */
  public static final long MAX_SCORE = 1L << 53;

  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final Pattern PERIOD = Pattern.compile("[A-Za-z0-9-]{1,32}");

  private Validation() {}

  public static String id(String value, String field) {
    if (value == null || !ID.matcher(value).matches()) {
      throw new InvalidRequestException(
          field + " must be 1-64 chars of lowercase letters, digits or '-'");
    }
    return value;
  }

  public static String period(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    if (!PERIOD.matcher(value).matches()) {
      throw new InvalidRequestException("period_key is malformed");
    }
    return value;
  }

  public static UUID playerId(String value) {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestException("player_id is required");
    }
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value.toLowerCase())) {
        throw new IllegalArgumentException();
      }
      return id;
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("player_id must be a UUID");
    }
  }

  public static long score(long score) {
    if (score < 0 || score > MAX_SCORE) {
      throw new InvalidRequestException("score must be between 0 and " + MAX_SCORE);
    }
    return score;
  }

  public static String idempotencyKey(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new InvalidRequestException("idempotency_key is required (max 128 chars)");
    }
    return value;
  }

  /** Page size: 0 means "use the default", negative or above {@link #MAX_PAGE} is rejected. */
  public static int pageSize(int n, int defaultSize) {
    if (n == 0) {
      return defaultSize;
    }
    if (n < 0 || n > MAX_PAGE) {
      throw new InvalidRequestException("n must be between 1 and " + MAX_PAGE);
    }
    return n;
  }

  public static int offset(int offset) {
    if (offset < 0) {
      throw new InvalidRequestException("offset must not be negative");
    }
    return offset;
  }

  public static int around(int around) {
    if (around < 0 || around > 50) {
      throw new InvalidRequestException("around must be between 0 and 50");
    }
    return around == 0 ? 2 : around;
  }
}
