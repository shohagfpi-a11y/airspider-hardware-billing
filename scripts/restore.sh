#!/usr/bin/env bash
# Restores a backup made by backup.sh. THIS REPLACES THE CURRENT DATABASE AND UPLOADS.
# Usage: scripts/restore.sh backups/db-YYYYMMDD-HHMMSS.dump backups/uploads-YYYYMMDD-HHMMSS.tgz
set -euo pipefail
cd "$(dirname "$0")/.."
DB_DUMP="${1:?path to db-*.dump}"; UPLOADS="${2:?path to uploads-*.tgz}"
[ -f "$DB_DUMP" ] && [ -f "$UPLOADS" ] || { echo "Backup files not found"; exit 1; }
set -a; . ./.env; set +a
echo "This will ERASE the current database '$POSTGRES_DB' and uploads and replace them with:"
echo "  $DB_DUMP"; echo "  $UPLOADS"
read -r -p "Type RESTORE to continue: " ANSWER
[ "$ANSWER" = "RESTORE" ] || { echo "Cancelled."; exit 1; }

echo ">> Stopping app and nginx..."
docker compose stop nginx app
echo ">> Recreating database..."
docker compose exec -T db psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS \"$POSTGRES_DB\" WITH (FORCE);" -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_USER\";"
echo ">> Restoring database..."
docker compose exec -T db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error < "$DB_DUMP"
echo ">> Restoring uploads..."
docker compose run --rm --no-deps --user root -v "$PWD/$(dirname "$UPLOADS"):/backup" --entrypoint sh app \
  -c "rm -rf /data/uploads/* && tar xzf /backup/$(basename "$UPLOADS") -C /data && chown -R app:app /data/uploads"
echo ">> Starting app and nginx..."
docker compose up -d app nginx
echo ">> Restore complete."
