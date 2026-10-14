# JFR profile during ReadHeavySimulation

- Recording: `-XX:StartFlightRecording=delay=45s,duration=120s,settings=profile` and `-XX:FlightRecorderOptions=stackdepth=512`, taken while 300 req/s of GetTopN(100)/GetPlayerRank ran against the 100k-player board (tracing at 100%).
- `flamegraph.html`: produced from the `.jfr` with async-profiler 3.0's `jfr2flame` converter.
- 629 execution samples (0 truncated). The process was about 3% CPU, so the service is mostly waiting on I/O at this load.

| Component (top-of-stack) | Samples |
|---|---|
| JDK / other | 26.1% |
| OpenTelemetry / Micrometer tracing | 20.8% |
| gRPC / Netty transport | 15.4% |
| Micrometer metrics | 8.3% |
| Protobuf serialization | 7.6% |
| Our service code | 7.5% |
| Lettuce (Redis client) | 6.7% |
| Spring Data Redis | 5.1% |
| Logging | 2.5% |

GC (G1): 26 young collections in 120 s, 94 ms total pause, longest 13.8 ms.
