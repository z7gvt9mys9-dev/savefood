"""initial schema

Revision ID: 0001
Revises:
Create Date: 2026-05-26 00:00:00.000000

This migration mirrors the schema the application builds at startup via the
`init_*_db()` helpers (CREATE TABLE + ALTER ADD COLUMN). Keep the two in sync:
if you add a column in an `init_*_db()`, add it here too.
"""
from typing import Sequence, Union
from alembic import op

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
            lat REAL,
            lon REAL,
            city TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS lots (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER NOT NULL REFERENCES shops(id),
            description TEXT,
            quantity INTEGER,
            expiry_date DATE,
            photo TEXT,
            address TEXT,
            time_slot TEXT,
            category TEXT,
            comment TEXT,
            city TEXT,
            status TEXT NOT NULL DEFAULT 'active',
            taken_at TIMESTAMP WITH TIME ZONE,
            taken_by TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS needy (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            contact TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            document TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS needy_profile (
            needy_id INTEGER PRIMARY KEY REFERENCES needy(id),
            address TEXT,
            family_size INTEGER,
            preferences TEXT,
            urgency TEXT,
            available_time TEXT,
            last_received_at TIMESTAMP WITH TIME ZONE,
            document TEXT,
            apartment TEXT,
            floor_num TEXT,
            entrance TEXT,
            city TEXT,
            lat REAL,
            lon REAL
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS tickets (
            id SERIAL PRIMARY KEY,
            needy_id INTEGER NOT NULL REFERENCES needy(id),
            items TEXT,
            available_time TEXT,
            address TEXT,
            lat REAL,
            lon REAL,
            lot_id INTEGER,
            status TEXT NOT NULL DEFAULT 'open',
            assigned_volunteer TEXT,
            assigned_volunteer_id INTEGER,
            fulfilled_at TIMESTAMP WITH TIME ZONE,
            apartment TEXT,
            floor_num TEXT,
            entrance TEXT,
            self_pickup BOOLEAN DEFAULT FALSE,
            delivery_photo TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS volunteers (
            id SERIAL PRIMARY KEY,
            name TEXT NOT NULL,
            contact TEXT,
            lat REAL,
            lon REAL,
            city TEXT,
            updated_at TIMESTAMP WITH TIME ZONE,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS volunteer_routes (
            id SERIAL PRIMARY KEY,
            volunteer_id INTEGER NOT NULL,
            points TEXT,
            status TEXT NOT NULL DEFAULT 'in_progress',
            lot_id INTEGER,
            started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
            finished_at TIMESTAMP WITH TIME ZONE
        )
    """)

    # Single shared notifications table (filtered per role by needy_id / shop_id /
    # volunteer_id). No FKs: a row carries only the id relevant to its recipient.
    op.execute("""
        CREATE TABLE IF NOT EXISTS notifications (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER,
            needy_id INTEGER,
            volunteer_id INTEGER,
            lot_id INTEGER,
            type TEXT,
            payload TEXT,
            read INTEGER NOT NULL DEFAULT 0,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
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

    op.execute("""
        CREATE TABLE IF NOT EXISTS audit_log (
            id SERIAL PRIMARY KEY,
            actor_username TEXT,
            action TEXT NOT NULL,
            target_type TEXT,
            target_id INTEGER,
            details TEXT,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)

    op.execute("""
        CREATE TABLE IF NOT EXISTS delivery_ratings (
            ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
            volunteer_id INTEGER,
            rating SMALLINT NOT NULL,
            comment TEXT,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
    """)


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS delivery_ratings CASCADE")
    op.execute("DROP TABLE IF EXISTS audit_log CASCADE")
    op.execute("DROP TABLE IF EXISTS telegram_link_tokens CASCADE")
    op.execute("DROP TABLE IF EXISTS notifications CASCADE")
    op.execute("DROP TABLE IF EXISTS volunteer_routes CASCADE")
    op.execute("DROP TABLE IF EXISTS volunteers CASCADE")
    op.execute("DROP TABLE IF EXISTS tickets CASCADE")
    op.execute("DROP TABLE IF EXISTS needy_profile CASCADE")
    op.execute("DROP TABLE IF EXISTS needy CASCADE")
    op.execute("DROP TABLE IF EXISTS lots CASCADE")
    op.execute("DROP TABLE IF EXISTS shops CASCADE")
    op.execute("DROP TABLE IF EXISTS users CASCADE")
