#!/usr/bin/env bash
# Backs up the PostgreSQL database AND the uploaded images into ./backups (timestamped).
# Usage: scripts/backup.sh [keep_days]   (default: delete backups older than 30 days)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo ".env not found"; exit 1; }
set -a; . ./.env; set +a
KEEP_DAYS="${1:-30}"
TS="$(date +%Y%m%d-%H%M%S)"
mkdir -p backups

echo ">> Dumping database..."
docker compose exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner > "backups/db-$TS.dump"

echo ">> Archiving uploads (logo, stamp, signature)..."
docker compose run --rm --no-deps --user root -v "$PWD/backups:/backup" --entrypoint tar app \
  czf "/backup/uploads-$TS.tgz" -C /data uploads

chmod 600 backups/db-"$TS".dump backups/uploads-"$TS".tgz
find backups -type f \( -name 'db-*.dump' -o -name 'uploads-*.tgz' \) -mtime +"$KEEP_DAYS" -delete
echo ">> Backup complete: backups/db-$TS.dump + backups/uploads-$TS.tgz"
echo "   Copy these files OFF the server (another machine or object storage)."
