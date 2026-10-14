package com.asmipahwa.leaderboard.grpc;

import com.asmipahwa.leaderboard.error.ConflictException;
import com.asmipahwa.leaderboard.error.InvalidRequestException;
import com.asmipahwa.leaderboard.error.NotFoundException;
import com.asmipahwa.leaderboard.error.UnavailableException;
import io.grpc.Status;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Maps exceptions to gRPC status codes, so every RPC reports errors the same way.
 *
 * <p>The code tells the client what to do: {@code INVALID_ARGUMENT} and {@code NOT_FOUND} mean "fix
 * the request", {@code ABORTED} means "retry the whole operation", {@code UNAVAILABLE} means "back
 * off and retry", and {@code INTERNAL} means "our bug".
 */
@GrpcAdvice
public class GrpcExceptionAdvice {

  private static final Logger log = LoggerFactory.getLogger(GrpcExceptionAdvice.class);

  @GrpcExceptionHandler(InvalidRequestException.class)
  public Status invalid(InvalidRequestException e) {
    return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
  }

  @GrpcExceptionHandler(NotFoundException.class)
  public Status notFound(NotFoundException e) {
    return Status.NOT_FOUND.withDescription(e.getMessage());
  }

  @GrpcExceptionHandler(ConflictException.class)
  public Status aborted(ConflictException e) {
    return Status.ABORTED.withDescription(e.getMessage());
  }

  @GrpcExceptionHandler({
    UnavailableException.class,
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class,
    RedisConnectionFailureException.class,
    TransientDataAccessException.class
  })
  public Status unavailable(Exception e) {
    log.warn("Dependency unavailable: {}", e.getMessage());
    return Status.UNAVAILABLE.withDescription("Temporarily unavailable, please retry");
  }

  @GrpcExceptionHandler(Exception.class)
  public Status internal(Exception e) {
    log.error("Unhandled error in gRPC call", e);
    return Status.INTERNAL.withDescription("Internal error");
  }
}
