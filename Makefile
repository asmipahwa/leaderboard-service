# Common developer commands. Run `make help` to list them.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME
GRADLE := ./gradlew

.PHONY: help up down seed reseed logs build coverage test it fmt demo-bot load-read load-write load-mixed kind-up deploy seed-k8s rollout-demo kind-down

help: ## Show this help
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-14s %s\n", $$1, $$2}'

up: ## Build the image and start postgres, redis and the app
	docker compose up -d --build --wait

down: ## Stop everything and delete the database volume
	docker compose down -v

seed: ## Load demo data (100k players, 500k events); skips if already seeded
	docker compose run --rm seed

reseed: ## Wipe and re-seed demo data
	SEED_RESET=true docker compose run --rm seed

logs: ## Tail app logs
	docker compose logs -f app

build: ## Format check, unit + integration tests, coverage gate
	$(GRADLE) build

coverage: ## Coverage report (open build/reports/jacoco/test/html/index.html)
	$(GRADLE) jacocoTestReport

test: ## Run unit tests
	$(GRADLE) test

it: ## Run integration tests (needs Docker)
	$(GRADLE) integrationTest

GATLING := $(GRADLE) :load-test:gatlingRun-com.asmipahwa.leaderboard.load

load-read: ## Gatling: GetTopN(100)/GetPlayerRank at 300 req/s on the 100k board (resume bullet 1)
	$(GATLING).ReadHeavySimulation

load-write: ## Gatling: 3 bursts of 2,000 concurrent SubmitScore, then verify zero lost writes (bullet 2)
	$(GATLING).ConcurrentWriteSimulation
	$(GRADLE) :load-test:verifyWrites

load-mixed: ## Gatling: 70% reads / 30% writes at 200 req/s for 5 minutes
	$(GATLING).MixedSimulation

# ---- Kubernetes (kind) ------------------------------------------------------------------
KIND_CLUSTER := leaderboard
KUBECTL := kubectl --context kind-$(KIND_CLUSTER) -n leaderboard

kind-up: ## Create the local kind cluster with metrics-server (HPA) and ingress-nginx
	kind create cluster --config k8s/kind-config.yaml
	kubectl --context kind-$(KIND_CLUSTER) apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml
	kubectl --context kind-$(KIND_CLUSTER) -n kube-system patch deployment metrics-server --type=json \
	  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
	kubectl --context kind-$(KIND_CLUSTER) apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml
	kubectl --context kind-$(KIND_CLUSTER) -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

deploy: ## Build the image, load it into kind and apply k8s/overlays/local
	docker build -t leaderboard-service:local .
	kind load docker-image leaderboard-service:local --name $(KIND_CLUSTER)
	kubectl --context kind-$(KIND_CLUSTER) apply -k k8s/overlays/local
	$(KUBECTL) rollout status statefulset/postgres --timeout=180s
	$(KUBECTL) rollout status deployment/leaderboard --timeout=300s

seed-k8s: ## Run the seed Job in the cluster (100k players)
	kubectl --context kind-$(KIND_CLUSTER) apply -k k8s/overlays/local/seed
	$(KUBECTL) wait --for=condition=complete job/leaderboard-seed --timeout=600s

rollout-demo: ## Zero-downtime proof: steady gRPC load while every pod is replaced
	./scripts/rollout-demo.sh

kind-down: ## Delete the kind cluster
	kind delete cluster --name $(KIND_CLUSTER)

demo-bot: ## Submit random scores so the live demo page (http://localhost:8080/demo) moves
	python3 demo/bot.py

fmt: ## Auto-format Java sources
	$(GRADLE) spotlessApply
