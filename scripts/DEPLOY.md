# Deploy & rollback (priority: deploy)

Deploys are currently manual (no CI deploy job — CI only runs tests/builds).
This runbook makes the manual steps repeatable and gives an explicit rollback
path so a bad deploy is recoverable instead of improvised.

## Preconditions

- CI is green on the commit being deployed (tests + frontend build + go vet).
- A fresh DB backup exists (`scripts/db_backup.sh`) — required before any deploy
  that includes a migration, so a broken migration is recoverable.
- You know the **current** image/commit so you can roll back to it.

## Deploy

```bash
cd /opt/savefood
git fetch && git checkout <new-tag-or-commit>

# 1. Back up first (rollback insurance for migrations).
BACKUP_DIR=/var/backups/savefood ./scripts/db_backup.sh

# 2. Build + start. Schema is created/migrated idempotently on startup
#    (init_db + ADD COLUMN IF NOT EXISTS); alembic migrations live in migrations/.
docker compose up -d --build

# 3. Verify health before declaring success. The backend healthcheck hits
#    /readyz (process up AND DB reachable); compose only marks it healthy then,
#    and the frontend waits for that.
docker compose ps
curl -fsS http://localhost:8000/healthz   # liveness (from inside the network)
curl -fsS http://localhost:8000/readyz    # readiness (DB reachable)
```

A deploy is "done" only when `docker compose ps` shows `backend` **healthy** and
`/readyz` returns `{"status":"ready"}`. If it stays unhealthy past
`start_period`, roll back.

## Rollback

```bash
cd /opt/savefood

# 1. Return code to the previous known-good commit/tag and rebuild.
git checkout <previous-good-commit>
docker compose up -d --build

# 2. If the bad deploy ran a destructive/irreversible migration, restore the
#    pre-deploy backup (see scripts/BACKUP.md). Forward-only migrations
#    (ADD COLUMN IF NOT EXISTS) are safe to leave; a column added by the new
#    version is simply unused by the old one.
CONFIRM=yes ./scripts/db_restore.sh /var/backups/savefood/savefood_<pre-deploy-ts>.sql.gz

# 3. Re-verify health.
docker compose ps && curl -fsS http://localhost:8000/readyz
```

## Known gaps (next steps, need infra decisions)

- No automated CI **deploy** job — deploys are run by hand from this runbook.
  Wiring `docker compose up -d --build` + the health gate into a GitHub Actions
  job needs server SSH/registry secrets that aren't in the repo.
- No blue/green or zero-downtime swap — `up -d --build` has a brief restart gap.
