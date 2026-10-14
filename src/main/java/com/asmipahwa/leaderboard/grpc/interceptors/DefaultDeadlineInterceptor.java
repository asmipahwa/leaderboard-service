package com.asmipahwa.leaderboard.grpc.interceptors;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Gives every unary call a deadline when the client didn't send one.
 *
 * <p>Without a deadline, a request stuck behind a slow dependency holds a thread forever, and a
 * small outage can snowball into thread exhaustion. Streaming calls are long-lived by design and
 * are left alone.
 */
@GrpcGlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DefaultDeadlineInterceptor implements ServerInterceptor {

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "grpc-deadline");
            thread.setDaemon(true);
            return thread;
          });
  private final long timeoutMillis;

  public DefaultDeadlineInterceptor(
      @Value("${leaderboard.grpc.default-deadline-ms:5000}") long timeoutMillis) {
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public <Q, R> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
    if (call.getMethodDescriptor().getType() != MethodType.UNARY
        || Context.current().getDeadline() != null) {
      return next.startCall(call, headers);
    }
    Context context =
        Context.current().withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS, scheduler);
    return Contexts.interceptCall(context, call, headers, next);
  }
}
