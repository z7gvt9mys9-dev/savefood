"""One in-progress route per volunteer (partial unique index)

Revision ID: 0009
Revises: 0008
Create Date: 2026-06-12 12:00:00.000000

Closes the SELECT-then-INSERT race in start_route: two parallel calls (e.g. a
double-click on two different lots) could both pass the «already has an active
route» check and create two in-progress routes. Same pattern as
uq_tickets_one_active_per_needy (migration history) — the DB is the arbiter.

Kept in sync with volunteer init_db().
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0009"
down_revision: Union[str, None] = "0008"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS uq_routes_one_active_per_volunteer
        ON volunteer_routes (volunteer_id)
        WHERE status = 'in_progress'
        """
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS uq_routes_one_active_per_volunteer")
