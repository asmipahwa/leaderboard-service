# Sprint Log

Short, honest notes per sprint: goal, what shipped, demo, and a mini retro.

## Sprint 1: Foundation

**Goal:** a runnable skeleton with the real schema, seed data, tests against real databases, and CI.

**Shipped**
- Gradle (Kotlin DSL) build on Java 21 + Spring Boot 3.3, formatted with Spotless.
- Flyway `V1__init.sql`: games, leaderboards, players, friendships, scores (with `version`), score_events, player_stats.
- JPA entities and repositories; `PeriodKeyResolver` for daily / ISO-weekly / season keys in UTC.
- `DataSeeder` (profile `seed`) using Postgres `COPY`: 100k players, ~500k friendships, 500k events → 393k best-score rows and 241k stats rows in **47 s** on an M4 laptop.
- Testcontainers base class (singleton Postgres 16 + Redis 7). `SchemaIT` proves a stale `@Version` update is rejected; `DataSeederIT` proves seeded best scores and stats match the event history.
- Multi-stage Dockerfile (non-root, layered jar), docker-compose, Makefile, CI workflow.

**Demo:** `make up && make seed`, then `curl localhost:8080/actuator/health/readiness` → `{"status":"UP"}`.

**Retro**
- Went well: deriving best scores and stats from a replayed event stream makes the seed data self-consistent, so later integration tests can trust it.
- To improve: my laptop's disk filled up mid-sprint and corrupted Docker's image cache (an `exec format error` from a half-written layer). Now I check free space before big builds.

## Sprint 2: gRPC + Redis

**Goal:** the real API (gRPC first, REST second) with rankings served from Redis sorted sets.

**Shipped**
- `leaderboard.v1` proto: SubmitScore, BatchSubmitScores, GetTopN, GetPlayerRank (around me), GetFriendsLeaderboard, StreamTopN (server streaming), GetPlayerStats, GetScoreHistory (keyset pagination).
- Redis sorted set per board period. Writes go to Postgres first, then one Lua script does `ZADD GT` and returns the old and new rank atomically.
- Standard competition ranking (1, 2, 2, 4), correct even when a page starts in the middle of a tie.
- `LeaderboardRebuilder`: streams a board from Postgres with keyset pagination and pipelined `ZADD GT`, behind a per-board lock (no cache stampede). `CacheWarmer` builds all current boards before the pod reports ready.
- gRPC status mapping (`INVALID_ARGUMENT`, `NOT_FOUND`, `UNAVAILABLE`, `INTERNAL`), default deadline interceptor, REST facade + Swagger UI.
- Tests: 54 unit tests, 16 integration tests. `RedisLossRecoveryIT` runs `FLUSHALL` and gets an identical top 100 back.

**Demo** (seeded 100k-player board): startup warm-up rebuilt the board in 1.1 s; a mid-table player submitting 70,000 jumped from rank 49,996 to rank 1.

**Retro**
- Went well: keeping protobuf out of the service layer made the REST facade a 100-line file.
- To improve: `localhost:5432` was taken by a Postgres already installed on my laptop, so the app silently connected to the wrong database. Compose now uses host port 5433.

## Sprint 3: Correctness

**Goal:** no lost or double-counted writes, and leaderboards that survive a Redis outage.

**Shipped**
- Optimistic locking retry loop: up to 5 retries with full-jitter exponential backoff (5 to 80 ms), metric `leaderboard_optimistic_lock_retries_total`, gRPC `ABORTED` when exhausted.
- Idempotency keys: Redis `SET NX` fast path (24 h), backed by the `UNIQUE` constraint in Postgres. A concurrent duplicate loses on the constraint, retries, and sees the stored event.
- Player stats via an atomic upsert (`x = x + 1`); achievements unlocked once via `INSERT ... ON CONFLICT DO NOTHING`.
- `RedisGuard` (Resilience4j circuit breaker, 500 ms Redis timeouts). Every Redis read has a Postgres fallback; Redis writes that fail after commit mark the board dirty for the reconciler.
- `Reconciler`: rebuilds dirty boards and repairs sampled drift with `ZADD GT`, metric `leaderboard_drift_fixed_total`.
- JaCoCo gate (80% lines on `service` + `cache`), enforced in CI. Current: **93.2%**.

**Demo:** `ConcurrentSubmitIT` released 2,500 submissions at once on 50 players:
`failed-requests=0 event-rows=2500 lost-writes=0 optimistic-lock-retries=5`.

**Retro**
- Went well: pausing the Redis container in a test (instead of stopping it) keeps its port, so the "Redis is down" test is reliable.
- To improve: two scripted find-and-replace edits silently missed because the formatter had reflowed the original lines. The Redis-outage test caught it. Now I verify every scripted edit.
- Learned: very few retries were needed. A conflict only happens when two requests for the same player **both** beat the stored best at the same moment, and most submissions don't beat the best at all.

## Sprint 4: Performance + Observability

**Goal:** prove the read and write claims with Gatling, and make the service debuggable.

**Shipped**
- Gatling module: read-heavy, concurrent-write (+ `WriteVerifier`), mixed and rolling-update simulations over gRPC. The official Gatling gRPC plugin turned out to be a 5-user trial, so the module uses the open-source phisgr plugin.
- Metrics (latency histograms per RPC, Redis and DB; lock retries; fallbacks; drift; bulkhead), Grafana dashboard, Prometheus alerts, OpenTelemetry traces to Jaeger, JSON logs with trace ids.
- Optimizations, all measured ([PERFORMANCE.md](PERFORMANCE.md)): Redis connection pool (GetTopN p95 669 → 4 ms), single-round-trip Lua reads, GetPlayerRank 7 → 1 round trip (server p95 4.07 → 1.29 ms), bulkhead + virtual threads + time-window breaker for bursts.
- JFR profile and flame graph. Debugging walkthrough with an injected slow query ([OBSERVABILITY.md](OBSERVABILITY.md)).
- WebSocket live leaderboard at `/demo` plus a score bot.

**Demo:** final results are GetTopN(100) **p95 3 ms** at 300 req/s on 100k players, 0 errors; **6,000 concurrent submissions (3 × 2,000 at once), 0 lost writes**, verified in Postgres and Redis.

**Retro**
- Went well: measuring before changing anything. The first fix (a missing connection pool) was a 160× win that I would never have guessed.
- Hard part: adding observability caused an intermittent failure in the concurrency test. Ruling suspects out one at a time, and re-running the old commit, showed it was a thundering herd and not any one library. A bulkhead fixed it.
- Learned: some "improvements" were within noise (single-Lua top-N, trace sampling). I report them that way instead of claiming a win.
- Environment: Docker Desktop's Scout CLI hooks made image pulls hang. A hook-free Docker config fixed it.
