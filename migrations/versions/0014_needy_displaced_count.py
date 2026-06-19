"""Recipient displacement counter for fair queueing (audit §59/Q1-C)

Revision ID: 0014
Revises: 0013
Create Date: 2026-06-19 12:00:00.000000

`needy_profile.displaced_count` counts how many times a recipient's reservation
was cancelled because a volunteer claimed the lot (their time window was closed
for that run, or they ranked below max_stops). It feeds the routing priority
score (§14) so repeatedly-bumped recipients climb the queue instead of losing
systematically, and is reset to 0 on fulfilment (set_profile_last_received).

Kept in sync with the needy init_db() ALTER (backend/needy/db.py).
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0014"
down_revision: Union[str, None] = "0013"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE needy_profile ADD COLUMN IF NOT EXISTS displaced_count INTEGER NOT NULL DEFAULT 0")


def downgrade() -> None:
    op.execute("ALTER TABLE needy_profile DROP COLUMN IF EXISTS displaced_count")
