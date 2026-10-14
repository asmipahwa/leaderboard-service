# ---- Build stage: compile with the Gradle wrapper and split the jar into layers ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Download dependencies in their own layer so code changes don't re-download the internet.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test
RUN java -Djarmode=layertools -jar build/libs/app.jar extract --destination extracted

# ---- Runtime stage: small JRE image, non-root user, layered jar ----
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.source="https://github.com/asmipahwa/leaderboard-service" \
      org.opencontainers.image.description="Game Stats & Leaderboard Service" \
      org.opencontainers.image.licenses="MIT"
# Fixed numeric UID so Kubernetes can verify runAsNonRoot.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# Least-frequently-changing layers first, so a code change only rebuilds the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
EXPOSE 8080 9090
# Size the heap from the container memory limit; crash fast (and get restarted) on OOM instead of
# limping along in a broken state.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
