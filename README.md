# Game Stats & Leaderboard Service

**Game Stats & Leaderboard Service: a gameplay-services-style backend that captures player stats and powers real-time global, friends, and seasonal leaderboards.**

[![CI](https://github.com/asmipahwa/leaderboard-service/actions/workflows/ci.yml/badge.svg)](https://github.com/asmipahwa/leaderboard-service/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)
![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791)
![Redis 7](https://img.shields.io/badge/Redis-7-DC382D)
![License: MIT](https://img.shields.io/badge/License-MIT-blue)

> 🚧 Work in progress. This README grows each sprint; see [docs/SPRINT_LOG.md](docs/SPRINT_LOG.md).

## Stack

Java 21 · Spring Boot 3.3 · gRPC / Protobuf · PostgreSQL 16 (source of truth, Flyway) · Redis 7 (sorted-set ranking cache) · Kubernetes (kind) · Docker · Testcontainers · Gatling · Prometheus / Grafana · OpenTelemetry / Jaeger · GitHub Actions

## Quickstart

Requires Docker. Java 21 is only needed to run tests outside Docker.

```bash
make up      # build the image, start postgres + redis + app
make seed    # load 3 games, 8 leaderboards, 100k players, 500k score events (~1 min)
curl localhost:8080/actuator/health
```

Other commands: `make help`.

## Data model

| Table | What it holds |
|---|---|
| `games`, `leaderboards` | Titles and their boards (sort order, reset period) |
| `players`, `friendships` | Who is ranked, and who is friends with whom |
| `scores` | **Best** score per player per board period, with a `version` column for optimistic locking |
| `score_events` | Every submission ever made, keyed by a unique idempotency key |
| `player_stats` | Running totals per player per game: matches, wins, total and best score |

Leaderboards reset by switching **period keys** (`all`, `2026-10-14`, `2026-W42`, `S1`), computed in UTC, instead of deleting data.

## Testing

```bash
make test   # unit tests
make it     # integration tests against real Postgres 16 + Redis 7 (Testcontainers)
```

## License

[MIT](LICENSE)
