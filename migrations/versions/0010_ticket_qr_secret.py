"""Per-ticket QR secret (unguessable delivery / self-pickup code)

Revision ID: 0010
Revises: 0009
Create Date: 2026-06-12 13:00:00.000000

The delivery QR used to be the predictable SF-{ticket_id}, which a volunteer
already sees in their route and a shop could brute-force for its own lots'
tickets. tickets.qr_secret makes the payload SF-{id}-{secret}: only the
recipient (who shows the QR) and an admin ever learn it, so a delivery can't
be confirmed — nor a self-pickup closed — without the recipient present.

Existing rows are backfilled with a random secret so in-flight tickets keep
working. Kept in sync with needy init_db().
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0010"
down_revision: Union[str, None] = "0009"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS qr_secret TEXT")
    op.execute(
        "UPDATE tickets SET qr_secret = substr(md5(random()::text || id::text), 1, 12) "
        "WHERE qr_secret IS NULL"
    )


def downgrade() -> None:
    op.execute("ALTER TABLE tickets DROP COLUMN IF EXISTS qr_secret")
