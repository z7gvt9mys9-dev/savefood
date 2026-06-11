"""C2C private donors: shops.kind

Revision ID: 0005
Revises: 0004
Create Date: 2026-06-12 00:00:00.000000

'business' (default — shop/cafe) | 'private' (a person donating surplus food).
Private donors reuse the whole shop flow; their lots require a photo and carry
a «Частный донор» badge on recipient/volunteer maps.

Kept in sync with `init_db()` in backend/shop/db.py.
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0005"
down_revision: Union[str, None] = "0004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE shops ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'business'")


def downgrade() -> None:
    op.execute("ALTER TABLE shops DROP COLUMN IF EXISTS kind")
