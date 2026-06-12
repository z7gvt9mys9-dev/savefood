"""Cold chain + geo-push + account deletion support

Revision ID: 0007
Revises: 0006
Create Date: 2026-06-12 06:00:00.000000

Roadmap «изменения существующего» batch:
- §47 Cold chain: lots.requires_cold (shop flags a lot that needs a fridge) and
  volunteers.has_thermal_bag (a volunteer who can carry such lots). A volunteer
  without a thermal bag cannot start a route on a cold-chain lot.
- §48 Geo-push subscription: needy_profile.geo_push_enabled lets a recipient
  opt out of «рядом появился подходящий лот» Web Push pings (default on).
- §49 Right to be forgotten: needy.status gains a 'deleted' value (no DDL — it
  is a plain status string); the data-erasure endpoint anonymizes the row.

Kept in sync with init_db()/init_ticket_extensions() in the backend modules.
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0007"
down_revision: Union[str, None] = "0006"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE lots ADD COLUMN IF NOT EXISTS requires_cold BOOLEAN NOT NULL DEFAULT FALSE")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS has_thermal_bag BOOLEAN NOT NULL DEFAULT FALSE")
    op.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS geo_push_enabled BOOLEAN NOT NULL DEFAULT TRUE")


def downgrade() -> None:
    op.execute("ALTER TABLE needy_profile DROP COLUMN IF EXISTS geo_push_enabled")
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS has_thermal_bag")
    op.execute("ALTER TABLE lots DROP COLUMN IF EXISTS requires_cold")
