#!/usr/bin/env bash
# Renew certificates (safe to run daily from cron; only renews when close to expiry).
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose --profile tools run --rm certbot renew --webroot -w /var/www/certbot --quiet
docker compose exec -T nginx nginx -s reload
