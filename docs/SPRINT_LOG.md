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
