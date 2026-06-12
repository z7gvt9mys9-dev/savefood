"""Delivery photo moderation: tickets.delivery_photo_status + AI verdict

Revision ID: 0006
Revises: 0005
Create Date: 2026-06-12 00:00:00.000000

Recipient delivery photos feed the public Impact feed (§36). They used to be
published instantly; now every photo is gated behind a moderation status
('pending' → 'approved'/'rejected') with an AI pre-check verdict shown in the
admin queue. Existing (already public) photos are grandfathered to 'approved'.

Kept in sync with `init_ticket_extensions()` in backend/database.py.
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0006"
down_revision: Union[str, None] = "0005"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_status TEXT")
    op.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_ai_verdict TEXT")
    op.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_ai_score REAL")
    op.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_ai_notes TEXT")
    op.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_reviewed_at TIMESTAMP WITH TIME ZONE")
    op.execute("UPDATE tickets SET delivery_photo_status = 'approved' "
               "WHERE delivery_photo IS NOT NULL AND delivery_photo_status IS NULL")
    op.execute("CREATE INDEX IF NOT EXISTS idx_tickets_photo_status "
               "ON tickets (delivery_photo_status) WHERE delivery_photo_status IS NOT NULL")


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_tickets_photo_status")
    op.execute("ALTER TABLE tickets DROP COLUMN IF EXISTS delivery_photo_reviewed_at")
    op.execute("ALTER TABLE tickets DROP COLUMN IF EXISTS delivery_photo_ai_notes")
    op.execute("ALTER TABLE tickets DROP COLUMN IF EXISTS delivery_photo_ai_score")
    op.execute("ALTER TABLE tickets DROP COLUMN IF EXISTS delivery_photo_ai_verdict")
    op.execute("ALTER TABLE tickets DROP COLUMN IF EXISTS delivery_photo_status")
