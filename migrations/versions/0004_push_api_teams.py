"""Web Push subscriptions, partner API keys, webhooks, volunteer teams

Revision ID: 0004
Revises: 0003
Create Date: 2026-06-11 12:00:00.000000

Wave 2 schema:
- push_subscriptions — Web Push (VAPID) endpoints per user (backend/push_service.py)
- api_keys           — Enterprise partner API, sha256-hashed keys (backend/partner_api.py)
- webhooks           — outgoing HMAC-signed ERP webhooks (backend/webhook_service.py)
- teams + volunteers.team_id — corporate volunteering (backend/volunteer/routes.py)

Kept in sync with `init_common_db()` (backend/database.py), `init_db()` in
backend/shop/db.py and backend/volunteer/db.py.
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0004"
down_revision: Union[str, None] = "0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS push_subscriptions (
            id SERIAL PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            endpoint TEXT UNIQUE NOT NULL,
            p256dh TEXT NOT NULL,
            auth TEXT NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_push_subscriptions_user ON push_subscriptions (user_id)")

    op.execute("""
        CREATE TABLE IF NOT EXISTS api_keys (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER NOT NULL REFERENCES shops(id),
            key_hash TEXT UNIQUE NOT NULL,
            prefix TEXT NOT NULL,
            revoked BOOLEAN NOT NULL DEFAULT FALSE,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
            last_used_at TIMESTAMP WITH TIME ZONE
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS webhooks (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER NOT NULL REFERENCES shops(id),
            url TEXT NOT NULL,
            secret TEXT NOT NULL,
            events TEXT NOT NULL DEFAULT '*',
            active BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
            last_status INTEGER,
            last_delivery_at TIMESTAMP WITH TIME ZONE
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_webhooks_shop ON webhooks (shop_id) WHERE active")

    op.execute("""
        CREATE TABLE IF NOT EXISTS teams (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            join_code TEXT UNIQUE NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)
    op.execute("ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS team_id INTEGER REFERENCES teams(id)")


def downgrade() -> None:
    op.execute("ALTER TABLE volunteers DROP COLUMN IF EXISTS team_id")
    op.execute("DROP TABLE IF EXISTS teams CASCADE")
    op.execute("DROP TABLE IF EXISTS webhooks CASCADE")
    op.execute("DROP TABLE IF EXISTS api_keys CASCADE")
    op.execute("DROP TABLE IF EXISTS push_subscriptions CASCADE")
