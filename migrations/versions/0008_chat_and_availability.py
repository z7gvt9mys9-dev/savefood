"""In-app chat + volunteer availability calendar

Revision ID: 0008
Revises: 0007
Create Date: 2026-06-12 07:00:00.000000

Roadmap «изменения существующего» batch 2:
- §53 In-app chat: ticket_messages backs a built-in volunteer↔recipient chat,
  replacing the Telegram-only relay (§22.7) for users who never linked Telegram.
- §54 Availability calendar: volunteers.availability holds weekly time windows
  (JSON) so the platform knows when a volunteer is free.

Kept in sync with init_ticket_extensions() / volunteer init_db().
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0008"
down_revision: Union[str, None] = "0007"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS ticket_messages (
            id SERIAL PRIMARY KEY,
            ticket_id INTEGER NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
            sender_role TEXT NOT NULL,
            sender_id INTEGER NOT NULL,
            body TEXT NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
        """
    )
    op.execute("CREATE INDEX IF NOT EXISTS idx_ticket_messages_ticket ON ticket_messages (ticket_id, id)")
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS availability TEXT")


def downgrade() -> None:
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS availability")
    op.execute("DROP INDEX IF EXISTS idx_ticket_messages_ticket")
    op.execute("DROP TABLE IF EXISTS ticket_messages")
