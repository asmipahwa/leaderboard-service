# API evolution

Game clients ship through app stores and stay installed for months, so the server must keep
talking to **every client version still in the wild**. The gRPC contract
([`leaderboard.proto`](../src/main/proto/leaderboard/v1/leaderboard.proto)) is therefore
evolved, never rewritten.

## Rules for `leaderboard.v1`

1. **Only add.** New fields, new RPCs and new enum values are fine. Never change a field's type,
   name meaning or number.
2. **Never reuse a field number.** Protobuf encodes fields by number, not name. If field 6 used to
   mean `platform` and is later reused for `region`, an old client's `platform="ios"` would be
   read as `region="ios"`. When a field is removed, mark it:
   ```proto
   message SubmitScoreRequest {
     reserved 7;              // was: string device_id
     reserved "device_id";
   }
   ```
3. **New fields must be optional in meaning.** Proto3 sends nothing for a default value, so the
   server must treat "absent" (`""`, `0`, `false`) as "old client" and behave as before.
4. **New response fields are safe.** Old clients keep them as *unknown fields* and ignore them.
5. **Schema changes pair with API changes, and are additive too** (nullable columns, no renames).
   During a rolling deploy, old and new pods run side by side against the same database.
6. **When to cut `leaderboard.v2`:** when the meaning must change incompatibly (e.g. scores
   become decimals, or ranking semantics change). v2 gets a new package and runs alongside v1,
   both backed by the same service code, until v1 traffic drops to zero (watch
   `leaderboard_query_seconds_count` by method).

## The change made in this repo

| Change | Field | Why it's compatible |
|---|---|---|
| `SubmitScoreRequest.platform` | `string platform = 6;` | Optional; empty means "old client". Stored in the new nullable `score_events.platform` column ([`V4__score_event_platform.sql`](../src/main/resources/db/migration/V4__score_event_platform.sql)) |
| `GetTopNResponse.entries_version` | `int64 entries_version = 4;` | Response-only. Increases when a score on the board improves (or the board is rebuilt), so a polling client can skip re-rendering. Old clients ignore it |

## Proof

[`ApiEvolutionIT`](../src/test/java/com/asmipahwa/leaderboard/ApiEvolutionIT.java) compiles a
**frozen copy** of the original contract
([`src/test/proto/leaderboard/v1frozen`](../src/test/proto/leaderboard/v1frozen/leaderboard_v1_frozen.proto),
same proto package so it is wire-identical, different Java package) and checks:

- an old client can submit a score and read the top N against the evolved server;
- the old client receives `entries_version` as an unknown field (number 4) and is unaffected;
- the old client's events are stored with `platform = NULL`;
- a new client's `platform` is stored, and `entries_version` moves only on real improvements.

## Checklist for the next change

- [ ] Field number never used before (check for `reserved` lines)
- [ ] Default value means "behave like before"
- [ ] Migration is additive / nullable
- [ ] Frozen-contract test still passes (`make it`)
- [ ] Docs and proto comments updated
