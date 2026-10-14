# Performance

Every number on this page comes from a run whose raw report is saved in [`docs/results/`](results/). Nothing is estimated or hand-entered. Where a difference is within run-to-run noise, it says so.

## Test setup

| | |
|---|---|
| Machine | Apple M4 laptop (10 cores, 16 GB RAM), macOS |
| Containers | Docker Desktop 4.81 (VM: 10 CPUs, 8 GB). `docker compose` stack: app, Postgres 16, Redis 7, Prometheus, Grafana, Jaeger |
| Service | Java 21 (Temurin JRE, Alpine), `-XX:MaxRAMPercentage=75`, Hikari pool 20, Lettuce pool 16, gRPC on virtual threads, **tracing on with 100% sampling** unless stated |
| Data | `make seed`: 100,000 players, 500,000 score events. The benchmark board `fc-mobile/season-1-high-score` ranks **all 100,000 players** |
| Load generator | Gatling 3.9.5 with the open-source [phisgr/gatling-grpc](https://github.com/phisgr/gatling-grpc) plugin, on the **same laptop** (it competes with the service for CPU, which makes results slightly pessimistic). One shared HTTP/2 channel, the way real gRPC clients connect |
| Warm-up | `ReadHeavySimulation` sends 20,000 unmeasured calls before Gatling starts recording (JIT, connection pools, Redis script cache) |
| Resolution | Gatling reports whole milliseconds. Server-side p95s come from the `leaderboard_query_seconds` histogram in Prometheus |

Why the official `io.gatling:gatling-grpc-java` plugin isn't used: it is a trial that stops tests above 5 virtual users. The community plugin has no limit and uses the same Gatling engine and reports.

## Results (final code)

| What | Load | Requests | Failed | p50 | p95 | p99 | Report |
|---|---|---|---|---|---|---|---|
| **GetTopN(100)**, 100k-player board | ramp to 300 req/s, then 2 min at 300 req/s (90% top-100, 10% my-rank) | 36,597 | **0** | 1 ms | **3 ms** | 5 ms | [readheavy-FINAL](results/2026-10-14-readheavy-FINAL/index.html) |
| GetPlayerRank (same run) | | 4,053 | 0 | 1 ms | 3 ms | 4 ms | same |
| Server-side p95 (Prometheus, same run) | | | | | GetTopN **1.68 ms**, GetPlayerRank 1.35 ms | | |
| **Concurrent SubmitScore** | 3 bursts of **2,000 simultaneous** calls on 500 shared players | 6,000 | **0** | 480 ms | 1,112 ms | 1,237 ms | [concurrent-write-FINAL](results/2026-10-14-concurrent-write-FINAL/index.html) |
| ↳ WriteVerifier | Postgres + Redis checked after the run | 6,000 event rows for 6,000 OK responses | | | | | **0 lost writes**, 21 optimistic-lock retries ([output](results/2026-10-14-concurrent-write-FINAL/write-verification.txt)) |
| **ConcurrentSubmitIT** (JUnit + Testcontainers) | 2,500 submissions released at once on 50 players | 2,500 | **0** | | | | **0 lost writes**, 15 retries ([output](results/2026-10-14-concurrent-submit-it/concurrent-submit-it.txt)) |
| Mixed traffic | 200 req/s for 5 min (50% top-100, 20% rank, 30% submit) | 60,000 | **0** | 2 ms | 5 ms | 7 ms | [mixed-5min](results/2026-10-14-mixed-5min/index.html) |

Write latency during a burst (p95 ~1.1 s) is queueing, not slowness: 2,000 transactions arrive in the same millisecond and Postgres drains them about 1,500 per second through 20 connections. Outside bursts, SubmitScore p95 is 7 ms (mixed run).

## Optimization log

### 1. Pool Redis connections. GetTopN p95: 669 ms → 4 ms

| | GetTopN(100) p95 | p99 (all requests) | max | Report |
|---|---|---|---|---|
| Before | **669 ms** | 3,051 ms | 8,950 ms | [readheavy-0-baseline](results/2026-10-14-readheavy-0-baseline/index.html) |
| After | **4 ms** | 5 ms | 24 ms | [readheavy-1-redis-pool](results/2026-10-14-readheavy-1-redis-pool/index.html) |

- **Symptom:** p50 was 2 ms but p95 was 669 ms. Most requests were fast, and some stalled for seconds.
- **Diagnosis:** Redis's `total_connections_received` went up by 21 for 20 GetTopN calls. The page read used Spring Data Redis *pipelining*, which needs a dedicated connection. Without a pool, Lettuce opened and closed a TCP connection for every request. The 2-minute run opened about 40,000 connections, and connection setup plus thousands of sockets in TIME_WAIT caused the stalls.
- **Fix:** `commons-pool2` plus `spring.data.redis.lettuce.pool.*`. After the fix, 20 calls opened 0 new connections (2 during pool warm-up).

### 2. One Lua round trip for a top-N page. Fewer round trips, latency change within noise

GetTopN used two round trips: a pipeline (state, size, range), then `HMGET` for the names. It now uses a single `EVALSHA` ([`top_n.lua`](../src/main/resources/scripts/top_n.lua)).

| | Gatling p95 | Server p95 | Report |
|---|---|---|---|
| Before (tracing on) | 4 ms | 2.17 ms | [readheavy-2](results/2026-10-14-readheavy-2-observability-on/index.html) |
| After | 3 ms | 1.51 ms | [readheavy-3](results/2026-10-14-readheavy-3-single-lua-read/index.html) |

**Honest read:** later runs of the *same* code measured a server p95 of 1.5 to 2.3 ms, so this 0.66 ms difference is inside run-to-run noise. The change is still worth keeping because it halves Redis round trips (2 → 1) and Redis commands per call, which matters more on a real network than on localhost.

### 3. One Lua round trip for "my rank + neighbours". GetPlayerRank server p95: 4.07 → 1.29 ms

GetPlayerRank made **7 sequential Redis calls**: state, ZSCORE, ZCOUNT, ZREVRANK, ZCARD, ZREVRANGE, HMGET. It now makes one ([`player_rank.lua`](../src/main/resources/scripts/player_rank.lua)). Measured with 50% of traffic on GetPlayerRank, to get about 20,000 samples per side:

| | Gatling p95 | Gatling p99 | Server p95 | Report |
|---|---|---|---|---|
| Before | 5 ms | 10 ms | **4.07 ms** | [readheavy-5](results/2026-10-14-readheavy-5-rank50-before/index.html) |
| After | 3 ms | 4 ms | **1.29 ms** | [readheavy-6](results/2026-10-14-readheavy-6-rank50-after-lua/index.html) |

The "after" build also switched gRPC to virtual threads (see 4). In the same pair of runs, GetTopN only moved from 1.89 to 1.64 ms, so most of the 3× improvement comes from removing round trips.

### 4. Make write bursts safe. ConcurrentSubmitIT: intermittent Redis drift → 6/6 clean

Adding observability (Sprint 4) added CPU per request, and `ConcurrentSubmitIT` started failing. Postgres was always correct (2,500 of 2,500 events), but Redis fell behind. Investigation steps, all from test logs:

1. **Breaker opened mid-burst.** Debug logs showed about 3,000 `QueryTimeoutException`s at the start of the burst. These were the first Redis calls (idempotency `GET`, name `HMGET`) of 2,500 requests arriving in the same instant.
2. **Ruled out the suspects one by one:** tracing off, Redis command tracing off, connection pool off, JDBC proxy off. Each still failed sometimes, so no single library was to blame. The same test on the Sprint 3 commit passed on the same machine, which confirmed a regression rather than an environment problem.
3. **Root cause:** a thundering herd. 2,500 requests hit Redis and the CPU at once, even though Postgres can only take 20 of them at a time. gRPC's default executor also created thousands of OS threads, and the count-based breaker (a 20-call window) tripped after a few timeouts.

Fixes, each a standard pattern:
- **A bulkhead:** at most 64 submissions run at once, and the rest wait in a fair queue ([`SubmissionBulkhead`](../src/main/java/com/asmipahwa/leaderboard/service/SubmissionBulkhead.java)).
- **Virtual threads** for gRPC handlers.
- **A time-based breaker window** (10 s, at least 100 calls).

| | ConcurrentSubmitIT, harsh config (OS threads, breaker min 10 calls) | Gatling burst p95 |
|---|---|---|
| Before | failed in 11 of 13 investigation runs (11–50 of 50 players with Redis ≠ Postgres; the breaker opened in 12 of 13) | 1,229 ms |
| After | **6 of 6 runs pass**, breaker never opened | 1,112 ms |

### Experiments that did not help (kept for honesty)

| Experiment | Result | Conclusion |
|---|---|---|
| Hikari pool 20 → 40 (write burst, both warmed) | p95 1,229 ms → 1,294 ms | No gain. The limit is Postgres commit throughput, not connections, so the pool stays at 20 |
| Trace sampling 100% → 10% (read run) | CPU 3.3% → 2.9%, p95 unchanged (4 ms) | The service isn't CPU-bound at 300 req/s. Production config uses 10% to save CPU; the local demo keeps 100% so every trace is visible |
| First write run after a restart | burst p95 2,984 ms vs 1,229 ms warmed | JIT warm-up of the write path. Benchmarks discard the first run |

## Profiling (Java Flight Recorder)

JFR was recorded for 120 s during the read benchmark (`settings=profile`, stack depth 512). The recording was converted to a flame graph with async-profiler's `jfr2flame`: [flamegraph.html](results/2026-10-14-jfr-read-load/flamegraph.html).

CPU samples by component (top-of-stack attribution):

| Component | Share of CPU samples |
|---|---|
| JDK / other | 26.1% |
| **OpenTelemetry / Micrometer tracing** | **20.8%** |
| gRPC / Netty transport | 15.4% |
| Micrometer metrics | 8.3% |
| Protobuf serialization | 7.6% |
| Our service code | 7.5% |
| Lettuce (Redis client) | 6.7% |
| Spring Data Redis | 5.1% |
| Logging | 2.5% |

- **Top hotspot:** span creation and context propagation from 100% trace sampling, the largest single consumer. That led to the sampling experiment above.
- **The service is mostly idle at 300 req/s:** about 3% process CPU. Our own code is 7.5% of samples, and the biggest piece inside it is building ranked entries (`Ranking.assign`, 1.1%).
- **GC:** 26 young collections in 120 s, 94 ms of total pause, longest pause 13.8 ms (G1). That explains the occasional p99 and max outliers, not the p95.

## Reproduce

```bash
make up && make seed
make load-read      # ReadHeavySimulation (≈3 min)
make load-write     # ConcurrentWriteSimulation + WriteVerifier
make load-mixed     # MixedSimulation (5 min)
make it             # includes ConcurrentSubmitIT
```
