"""initial schema

Revision ID: 0001
Revises:
Create Date: 2026-05-26 00:00:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id SERIAL PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            hashed_password TEXT NOT NULL,
            role TEXT NOT NULL,
            related_id INTEGER,
            is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
            telegram_chat_id TEXT,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS shops (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            contact TEXT,
            address TEXT,
            lat DOUBLE PRECISION,
            lon DOUBLE PRECISION,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS lots (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
            description TEXT NOT NULL,
            quantity DOUBLE PRECISION DEFAULT 1,
            category TEXT,
            expiry_date DATE,
            time_slot TEXT,
            photo TEXT,
            address TEXT,
            comment TEXT,
            status TEXT NOT NULL DEFAULT 'active',
            taken_at TIMESTAMP WITH TIME ZONE,
            taken_by TEXT,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS needy (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            contact TEXT,
            document TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS needy_profile (
            id SERIAL PRIMARY KEY,
            needy_id INTEGER NOT NULL UNIQUE REFERENCES needy(id) ON DELETE CASCADE,
            address TEXT,
            lat DOUBLE PRECISION,
            lon DOUBLE PRECISION,
            family_size INTEGER DEFAULT 1,
            preferences TEXT,
            urgency TEXT DEFAULT 'normal',
            available_time TEXT,
            last_received_at TIMESTAMP WITH TIME ZONE
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS tickets (
            id SERIAL PRIMARY KEY,
            needy_id INTEGER NOT NULL REFERENCES needy(id) ON DELETE CASCADE,
            lot_id INTEGER REFERENCES lots(id) ON DELETE SET NULL,
            items TEXT,
            address TEXT,
            lat DOUBLE PRECISION,
            lon DOUBLE PRECISION,
            available_time TEXT,
            status TEXT NOT NULL DEFAULT 'open',
            assigned_volunteer TEXT,
            fulfilled_at TIMESTAMP WITH TIME ZONE,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS volunteers (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            contact TEXT,
            lat DOUBLE PRECISION,
            lon DOUBLE PRECISION,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS volunteer_routes (
            id SERIAL PRIMARY KEY,
            volunteer_id INTEGER NOT NULL REFERENCES volunteers(id) ON DELETE CASCADE,
            lot_id INTEGER REFERENCES lots(id) ON DELETE SET NULL,
            points TEXT NOT NULL DEFAULT '[]',
            status TEXT NOT NULL DEFAULT 'in_progress',
            started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
            finished_at TIMESTAMP WITH TIME ZONE,
            volunteer_name TEXT
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS notifications (
            id SERIAL PRIMARY KEY,
            needy_id INTEGER REFERENCES needy(id) ON DELETE CASCADE,
            type TEXT NOT NULL,
            payload TEXT,
            read INTEGER NOT NULL DEFAULT 0,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS shop_notifications (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER REFERENCES shops(id) ON DELETE CASCADE,
            type TEXT NOT NULL,
            payload TEXT,
            read INTEGER NOT NULL DEFAULT 0,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS volunteer_notifications (
            id SERIAL PRIMARY KEY,
            volunteer_id INTEGER REFERENCES volunteers(id) ON DELETE CASCADE,
            type TEXT NOT NULL,
            payload TEXT,
            read INTEGER NOT NULL DEFAULT 0,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS telegram_link_tokens (
            id SERIAL PRIMARY KEY,
            token TEXT UNIQUE NOT NULL,
            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS telegram_link_tokens CASCADE")
    op.execute("DROP TABLE IF EXISTS volunteer_notifications CASCADE")
    op.execute("DROP TABLE IF EXISTS shop_notifications CASCADE")
    op.execute("DROP TABLE IF EXISTS notifications CASCADE")
    op.execute("DROP TABLE IF EXISTS volunteer_routes CASCADE")
    op.execute("DROP TABLE IF EXISTS volunteers CASCADE")
    op.execute("DROP TABLE IF EXISTS tickets CASCADE")
    op.execute("DROP TABLE IF EXISTS needy_profile CASCADE")
    op.execute("DROP TABLE IF EXISTS needy CASCADE")
    op.execute("DROP TABLE IF EXISTS lots CASCADE")
    op.execute("DROP TABLE IF EXISTS shops CASCADE")
    op.execute("DROP TABLE IF EXISTS users CASCADE")
