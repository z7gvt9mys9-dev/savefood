# Database backups & restore (priority: backups)

The SaveFood data lives in the `postgres_data` Docker volume. A lost or corrupt
volume with no backup means total data loss, so backups are mandatory.

## What we back up

`scripts/db_backup.sh` runs `pg_dump` inside the `postgres` container and writes
a timestamped, gzip-compressed plain-SQL dump to `BACKUP_DIR`. It:

- verifies the dump has a valid `pg_dump` header (no silent empty backups),
- prunes dumps older than `RETENTION_DAYS` (default 14),
- exits non-zero on any failure so cron/monitoring can alert.

Uploaded files (delivery photos, shop logos) live in separate Docker volumes
(`shop_uploads`, `needy_uploads`, `volunteer_uploads`). Volunteer identity/KYC
documents are deliberately **not** backed up — they are deleted after a
moderation decision, or purged by the `kyc_doc_retention` background sweep after
`KYC_DOC_RETENTION_HOURS` (default 72) if never moderated (§5). Recipients no
longer upload KYC documents; keep the legacy `needy_uploads` volume mounted only
until every V5 cleanup tombstone is complete. Back up the other upload volumes
separately if photo history matters.

## Schedule (cron)

```cron
# daily at 03:30, log to syslog
30 3 * * * cd /opt/savefood && BACKUP_DIR=/var/backups/savefood ./scripts/db_backup.sh 2>&1 | logger -t savefood-backup
```

Ship the dumps off-host (object storage / another machine) — a backup on the
same disk as the DB does not survive disk loss. Example after the cron line:
`aws s3 sync /var/backups/savefood s3://savefood-backups/db/`.

## Monitoring

A backup that silently stops is worse than no backup. Alert if:

- the cron job exits non-zero (the script does on any failure), or
- no new `savefood_*.sql.gz` appeared in `BACKUP_DIR` in the last 25 h.

Wire the failure into the same channel as `SUPPORT_CHAT_ID` / Sentry.

## Restore drill (do this regularly — an untested backup is a guess)

```bash
# 1. Spin up a throwaway DB (or a staging stack) and point COMPOSE/PG vars at it.
# 2. Restore the latest dump:
CONFIRM=yes ./scripts/db_restore.sh /var/backups/savefood/savefood_<ts>.sql.gz
# 3. Smoke-check: row counts, a recent order, a login.
```

Record the date of the last successful restore — if you can't name it, treat the
backups as unverified.
