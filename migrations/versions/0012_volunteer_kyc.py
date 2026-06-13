"""Volunteer identity KYC (status + AI verdict columns)

Revision ID: 0012
Revises: 0011
Create Date: 2026-06-13 12:00:00.000000

Volunteers physically carry food from shops to recipients, so an unverified
account is the platform's main fraud surface (anyone grabbing a lot and
vanishing). This mirrors the needy KYC: an identity document is AI-pre-checked
and a moderator approves. Only 'approved' volunteers may claim routes
(gated in start_route, env VOLUNTEER_KYC_REQUIRED).

`status` is added WITHOUT a default so pre-existing volunteers (NULL right after
the column appears) can be grandfathered to 'approved' — locking out everyone
who was active before KYC existed would be worse than the fraud it guards.
New registrations set status='pending' explicitly. Kept in sync with
volunteer/db.py init_db().
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0012"
down_revision: Union[str, None] = "0011"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS status TEXT")
    op.execute("UPDATE volunteers SET status = 'approved' WHERE status IS NULL")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS document TEXT")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_score REAL")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_verdict TEXT")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_notes TEXT")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS kyc_checked_at TIMESTAMP WITH TIME ZONE")


def downgrade() -> None:
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS kyc_checked_at")
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS kyc_notes")
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS kyc_verdict")
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS kyc_score")
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS document")
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS status")
