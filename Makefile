# Common developer commands. Run `make help` to list them.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
export JAVA_HOME
GRADLE := ./gradlew

.PHONY: help up down seed reseed logs build coverage test it fmt

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

fmt: ## Auto-format Java sources
	$(GRADLE) spotlessApply
