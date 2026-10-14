#!/usr/bin/env bash
# Zero-downtime proof: steady gRPC traffic (Gatling RollingUpdateSimulation) while
# `kubectl rollout restart` replaces every pod. The Gatling assertion fails the run if even one
# request fails. A transcript is saved to load-test/build/evidence/.
set -euo pipefail

CONTEXT="kind-leaderboard"
NS="leaderboard"
RATE="${RATE:-50}"
DURATION="${DURATION:-180}"
OUT="load-test/build/evidence/rollout-$(date -u +%Y%m%dT%H%M%SZ).txt"
mkdir -p "$(dirname "$OUT")"
k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }
log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT"; }

log "Rolling update demo: ${RATE} req/s for ${DURATION}s against localhost:30090 (gRPC NodePort)"
k get deploy leaderboard -o jsonpath='{.spec.strategy}{"\n"}' | tee -a "$OUT"
k get pods -l app=leaderboard -o wide | tee -a "$OUT"

./gradlew -q :load-test:gatlingRun-com.asmipahwa.leaderboard.load.RollingUpdateSimulation \
  -Pgrpc.port=30090 -Prate="$RATE" -Pduration="$DURATION" > "$OUT.gatling" 2>&1 &
GATLING=$!

sleep 40
log "Steady traffic for 40s. Restarting every pod now."
k rollout restart deployment/leaderboard | tee -a "$OUT"
k get pods -l app=leaderboard -w --output-watch-events 2>&1 | while read -r line; do
  echo "[$(date -u +%H:%M:%S)] $line"
done >> "$OUT" &
WATCH=$!
k rollout status deployment/leaderboard --timeout=600s | while read -r line; do log "$line"; done
log "Rollout finished. Pods now:"
k get pods -l app=leaderboard -o wide | tee -a "$OUT"
kill "$WATCH" 2>/dev/null || true

log "Waiting for Gatling to finish..."
STATUS=0
wait "$GATLING" || STATUS=$?
sed -n '/---- Global Information/,/====/p' "$OUT.gatling" | tee -a "$OUT"
grep -E "count of failed events|Please open" "$OUT.gatling" | tee -a "$OUT"
log "Gatling exit status: $STATUS (0 = zero failed requests)"
log "Transcript: $OUT"
exit "$STATUS"
