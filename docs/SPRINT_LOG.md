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
