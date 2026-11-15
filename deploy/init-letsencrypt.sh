#!/usr/bin/env bash
# First-time HTTPS setup for the domain in .env (run once, after DNS points to this VPS).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Create .env from .env.example first."; exit 1; }
set -a; . ./.env; set +a
: "${DOMAIN:?DOMAIN missing in .env}"; : "${LETSENCRYPT_EMAIL:?LETSENCRYPT_EMAIL missing in .env}"
ACTIVE=deploy/nginx/active
mkdir -p "$ACTIVE"
cp deploy/nginx/proxy_params.inc "$ACTIVE/proxy_params.inc"

echo ">> Starting database and application..."
docker compose up -d db app

echo ">> Starting nginx with a temporary HTTP-only config for the certificate challenge..."
sed "s/__DOMAIN__/$DOMAIN/g" deploy/nginx/http-only.conf.template > "$ACTIVE/default.conf"
docker compose up -d nginx

echo ">> Requesting certificate for $DOMAIN ..."
docker compose --profile tools run --rm certbot certonly --webroot -w /var/www/certbot \
  -d "$DOMAIN" --email "$LETSENCRYPT_EMAIL" --agree-tos --no-eff-email --non-interactive

echo ">> Switching nginx to HTTPS..."
sed "s/__DOMAIN__/$DOMAIN/g" deploy/nginx/https.conf.template > "$ACTIVE/default.conf"
docker compose exec nginx nginx -s reload
echo ">> Done. Open https://$DOMAIN  (first-time setup: see docs/DEPLOYMENT.md, step 7)"
