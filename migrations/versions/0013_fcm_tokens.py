"""FCM device tokens for the native Android app

Revision ID: 0013
Revises: 0012
Create Date: 2026-06-14 12:00:00.000000

The native Android app receives pushes via Firebase Cloud Messaging (FCM HTTP
v1) instead of the browser's Web Push (VAPID). Tokens live in their own table
alongside `push_subscriptions` so the two channels stay independent — Web Push
keeps working untouched, FCM is purely additive and gated behind FCM_ENABLED.

`role`/`related_id` are denormalized onto the row so push_service.notify_role()
can dispatch by the same (role, related_id) key the Telegram and Web Push paths
already use, without joining back through `users`. The `user_id` FK still owns
the row so deleting an account cascades its devices.

Kept in sync with `init_common_db()` (backend/database.py).
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0013"
down_revision: Union[str, None] = "0012"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS fcm_tokens (
            id SERIAL PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            token TEXT UNIQUE NOT NULL,
            role TEXT NOT NULL,
            related_id INTEGER,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_fcm_tokens_role_related ON fcm_tokens (role, related_id)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON fcm_tokens (user_id)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS fcm_tokens CASCADE")
