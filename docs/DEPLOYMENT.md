# Deployment guide: Ubuntu VPS + Docker Compose + Nginx + HTTPS

One installation = one company = one stack (PostgreSQL + app + Nginx), its own domain, database and uploads.
For another customer, repeat this guide on another VPS (or another directory/ports with its own `.env`).

Nothing here has been run against your servers. Replace the placeholders (`billing.example.com`, passwords) yourself;
never paste real secrets into chat or into git.

## 0. What you need

* An Ubuntu 22.04/24.04 VPS (2 GB RAM is comfortable), root or sudo access, ports 80 and 443 open.
* The customer's domain (e.g. `billing.customer.com`) and access to its DNS.

## 1. Point the domain to the VPS

In the DNS panel of the domain, create an **A record**:

| Type | Name | Value | TTL |
|---|---|---|---|
| A | `billing` (or `@` for the root) | `<your VPS public IPv4>` | 300 |

(Add an `AAAA` record only if the VPS has IPv6 and port 80/443 work over it.) Check from your PC:
`nslookup billing.customer.com` must show the VPS IP **before** requesting the certificate.

## 2. Prepare the server (once)

```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install ca-certificates curl git ufw
# Docker Engine + Compose plugin (official convenience script)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"      # log out and in again afterwards
# firewall: SSH, HTTP, HTTPS only (PostgreSQL and the app are NOT exposed)
sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw --force enable
```

## 3. Get the code onto the server

```bash
sudo mkdir -p /opt/hardware-billing && sudo chown "$USER" /opt/hardware-billing
# copy the project folder here (scp/rsync/git), e.g. from your PC:
#   rsync -av --exclude target --exclude data ./hardware-billing/ user@VPS:/opt/hardware-billing/
cd /opt/hardware-billing
```

## 4. Configure

```bash
cp .env.example .env
nano .env        # set DOMAIN, LETSENCRYPT_EMAIL and a LONG RANDOM POSTGRES_PASSWORD
chmod 600 .env
```

Generate a password with `openssl rand -base64 32`. Keep `SETUP_TOKEN` empty to get a random one-time token
(recommended), or set your own long random value.

## 5. Start and enable HTTPS

```bash
./deploy/init-letsencrypt.sh
```

This builds the image (first build takes a few minutes), starts PostgreSQL and the app, starts Nginx with a temporary
HTTP-only config, obtains a Let's Encrypt certificate for `DOMAIN`, then switches Nginx to HTTPS.
All containers use `restart: unless-stopped` and have health checks (`docker compose ps`).

Renew certificates automatically (daily check, renews only when close to expiry):

```bash
( crontab -l 2>/dev/null; echo '17 3 * * * cd /opt/hardware-billing && ./deploy/renew-cert.sh >> /var/log/hb-cert.log 2>&1' ) | crontab -
```

## 6. Database migrations

Flyway runs automatically when the app starts and applies any new `V*.sql` files in
`src/main/resources/db/migration` in order. Applied migrations are never edited (checksums are verified). Check with:

```bash
docker compose logs app | grep -i flyway
```

## 7. First administrator setup (no default credentials exist)

1. Read the one-time token: `docker compose logs app | grep -A2 "FIRST-TIME SETUP"` (or use your `SETUP_TOKEN`).
2. Open `https://billing.customer.com/setup`, enter the token, the company details and the administrator
   username/password (minimum 10 characters).
3. After submission `/setup` closes permanently and the token stops working. Sign in, then add banks (Admin → Bank accounts),
   upload logo/stamp/signature and set invoice prefix/next number (Admin → Company settings), and create staff users.

## 8. Backups and restore

Back up **both** the database and the uploads (logo/stamp/signature):

```bash
./scripts/backup.sh            # creates backups/db-<timestamp>.dump and backups/uploads-<timestamp>.tgz, keeps 30 days
```

Daily at 02:30 and copy the files off the server (another machine or object storage, e.g. `rclone`/`scp`):

```bash
( crontab -l 2>/dev/null; echo '30 2 * * * cd /opt/hardware-billing && ./scripts/backup.sh >> /var/log/hb-backup.log 2>&1' ) | crontab -
```

Restore (replaces the current database and uploads; asks you to type RESTORE):

```bash
./scripts/restore.sh backups/db-YYYYMMDD-HHMMSS.dump backups/uploads-YYYYMMDD-HHMMSS.tgz
```

**Test a restore on a spare machine before you rely on it.**

## 9. Upgrade

```bash
cd /opt/hardware-billing
# put the new code in this folder (rsync/git pull), then:
./scripts/upgrade.sh     # backup -> rebuild image -> restart -> waits for healthy; Flyway migrates automatically
```

## 10. Rollback

* App-only problem (no new migration applied): put the previous code back, `docker compose build app && docker compose up -d app`.
* A migration was applied and you must go back: restore the backup that `upgrade.sh` took *before* the upgrade
  (`./scripts/restore.sh <db dump> <uploads tgz>`) **and** deploy the previous code. Flyway migrations are forward-only,
  so the database must be restored, not “un-migrated”. Data entered after the backup is lost, so decide quickly.

## 11. Day-to-day operations

```bash
docker compose ps                    # status/health
docker compose logs -f app           # application log (no passwords/bank details are logged)
docker compose restart app
docker compose down                  # stop (volumes/data are kept)   !! never use "down -v" unless you want to ERASE data
```

## 12. Security checklist

* `.env` readable only by you (`chmod 600`), never committed. Strong `POSTGRES_PASSWORD`.
* Only 22/80/443 open (ufw). PostgreSQL and the app have **no published ports**.
* Cookies are `Secure` + `HttpOnly` + `SameSite=Lax`; Nginx sends HSTS, the app locks accounts/IPs after repeated failed
  logins, Nginx additionally rate-limits `/login`.
* Keep the server patched (`unattended-upgrades`), and keep off-server backups.
* The `local` Spring profile (embedded database, insecure cookies, optional demo data with well-known passwords) must
  **never** be enabled on a server; the Docker setup does not use it.
