#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/savefood}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
POSTGRES_DB="${POSTGRES_DB:-savefood}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
COMPOSE="${COMPOSE:-docker compose}"

mkdir -p "$BACKUP_DIR"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/savefood_${ts}.sql.gz"
tmp="${out}.partial"

cleanup() { rm -f "$tmp"; }
trap cleanup EXIT

$COMPOSE exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner \
  | gzip -c > "$tmp"

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

find "$BACKUP_DIR" -name 'savefood_*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
echo "pruned dumps older than ${RETENTION_DAYS} days in $BACKUP_DIR"
