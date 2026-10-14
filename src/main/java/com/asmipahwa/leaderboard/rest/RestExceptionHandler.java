package com.asmipahwa.leaderboard.rest;

import com.asmipahwa.leaderboard.error.ConflictException;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.error.UnavailableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Maps the same domain exceptions the gRPC advice handles to HTTP status codes, returned as RFC
 * 7807 problem details.
 */
@RestControllerAdvice
public class RestExceptionHandler {

  @ExceptionHandler(InvalidRequestException.class)
  public ProblemDetail invalid(InvalidRequestException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
  public ProblemDetail validation(Exception e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request failed validation");
  }

  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail notFound(NotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ProblemDetail conflict(ConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler({
    UnavailableException.class,
    DataAccessResourceFailureException.class,
    RedisConnectionFailureException.class
  })
  public ProblemDetail unavailable(Exception e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable, please retry");
  }
}
