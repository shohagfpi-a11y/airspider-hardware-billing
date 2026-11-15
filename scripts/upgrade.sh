#!/usr/bin/env bash
# Upgrade to the code currently in this folder: backup -> rebuild -> restart (Flyway migrates the DB on start).
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/backup.sh
docker compose build app
docker compose up -d app
echo ">> Waiting for the app to become healthy..."
for i in $(seq 1 40); do
  s="$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || echo starting)"
  [ "$s" = "healthy" ] && { echo ">> Upgrade OK."; exit 0; }
  sleep 5
done
echo "!! App did not become healthy. See: docker compose logs app. To roll back see docs/DEPLOYMENT.md (Rollback)."; exit 1
