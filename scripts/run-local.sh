#!/usr/bin/env bash
# Local run with embedded PostgreSQL. Add SEED_DEMO=true for development demo data.
cd "$(dirname "$0")/.."
SPRING_PROFILES_ACTIVE=local SEED_DEMO="${SEED_DEMO:-false}" ./mvnw spring-boot:run
