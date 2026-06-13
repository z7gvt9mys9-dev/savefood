#!/usr/bin/env bash
# Restore a SaveFood Postgres backup produced by db_backup.sh.
#
# DANGER: this applies the dump on top of the current database. Run it against
# a fresh/empty DB for a clean restore, or accept that existing rows may collide.
# Requires explicit CONFIRM=yes so it can never run by accident.
#
# Usage:
#   CONFIRM=yes ./scripts/db_restore.sh /var/backups/savefood/savefood_YYYY...sql.gz
set -euo pipefail

DUMP="${1:-}"
POSTGRES_DB="${POSTGRES_DB:-savefood}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
COMPOSE="${COMPOSE:-docker compose}"

if [ -z "$DUMP" ] || [ ! -f "$DUMP" ]; then
  echo "usage: CONFIRM=yes $0 <path/to/savefood_*.sql.gz>" >&2
  exit 2
fi
if [ "${CONFIRM:-}" != "yes" ]; then
  echo "refusing to restore into '$POSTGRES_DB' without CONFIRM=yes" >&2
  exit 2
fi

# Validate BEFORE streaming into psql, so a truncated/corrupt dump can't
# half-apply (DROP/CREATE/COPY part-way) into the target database.
if ! gzip -t "$DUMP" 2>/dev/null; then
  echo "ERROR: $DUMP failed gzip integrity check — refusing to restore" >&2
  exit 1
fi
header="$(gzip -dc "$DUMP" 2>/dev/null | head -n 20 || true)"
case "$header" in
  *"PostgreSQL database dump"*) : ;;
  *) echo "ERROR: $DUMP is not a pg_dump backup — refusing to restore" >&2; exit 1 ;;
esac

echo "restoring $DUMP into database '$POSTGRES_DB' ..."
# ON_ERROR_STOP=1 so a broken dump fails loudly instead of half-applying.
gzip -dc "$DUMP" | $COMPOSE exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1
echo "restore complete"
