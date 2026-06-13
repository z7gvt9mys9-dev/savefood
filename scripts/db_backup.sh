#!/usr/bin/env bash
# Logical backup of the SaveFood Postgres DB (priority: backups).
#
# Runs pg_dump inside the postgres container, writes a timestamped gzip to
# BACKUP_DIR, verifies the dump is a real pg_dump (not a truncated/empty file),
# prunes dumps older than RETENTION_DAYS, and exits non-zero on any failure so
# cron / monitoring can alert on a missed backup.
#
# Usage (from the repo root, where docker-compose.yml lives):
#   BACKUP_DIR=/var/backups/savefood ./scripts/db_backup.sh
#
# Cron (daily 03:30, log to syslog):
#   30 3 * * * cd /opt/savefood && BACKUP_DIR=/var/backups/savefood \
#     ./scripts/db_backup.sh 2>&1 | logger -t savefood-backup
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/savefood}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
POSTGRES_DB="${POSTGRES_DB:-savefood}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
# Override to "docker-compose" on older hosts, or a direct "pg_dump"-prefixing
# wrapper if Postgres is not in compose.
COMPOSE="${COMPOSE:-docker compose}"

mkdir -p "$BACKUP_DIR"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/savefood_${ts}.sql.gz"
tmp="${out}.partial"

cleanup() { rm -f "$tmp"; }
trap cleanup EXIT

# Plain SQL (not -Fc) so the dump is grep-able and portable across PG minor
# versions; gzip keeps it compact. --no-owner so it restores under any role.
$COMPOSE exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner \
  | gzip -c > "$tmp"

# Guard against keeping a truncated/empty file as "the backup" — a silent
# failure here is exactly how teams discover their backups were useless.
# gzip -t validates the whole stream (catches truncation/empty/corruption);
# the header check confirms it's actually a pg_dump. We capture the head into a
# variable (with `|| true`) so the producer's SIGPIPE under `set -o pipefail`
# can't false-fail the check.
if ! gzip -t "$tmp" 2>/dev/null; then
  echo "ERROR: backup failed gzip integrity check (truncated/empty?) — aborting" >&2
  exit 1
fi
header="$(gzip -dc "$tmp" 2>/dev/null | head -n 20 || true)"
case "$header" in
  *"PostgreSQL database dump"*) : ;;
  *)
    echo "ERROR: dump did not contain a valid pg_dump header — aborting" >&2
    exit 1
    ;;
esac

mv "$tmp" "$out"
trap - EXIT
echo "backup OK: $out ($(du -h "$out" | cut -f1))"

# Retention: prune dumps older than RETENTION_DAYS.
find "$BACKUP_DIR" -name 'savefood_*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
echo "pruned dumps older than ${RETENTION_DAYS} days in $BACKUP_DIR"
