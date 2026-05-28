import os
import psycopg2
from psycopg2.extras import RealDictCursor
from contextlib import contextmanager

# Database configuration from environment variables
DB_HOST = os.getenv("DB_HOST", "localhost")
DB_NAME = os.getenv("DB_NAME", "savefood")
DB_USER = os.getenv("DB_USER", "postgres")
DB_PASS = os.getenv("DB_PASS", "postgres")
DB_PORT = os.getenv("DB_PORT", "5432")

DATABASE_URL = f"host={DB_HOST} dbname={DB_NAME} user={DB_USER} password={DB_PASS} port={DB_PORT}"

def get_conn():
    conn = psycopg2.connect(DATABASE_URL)
    return conn

@contextmanager
def get_db_cursor():
    conn = get_conn()
    cur = conn.cursor(cursor_factory=RealDictCursor)
    try:
        yield cur
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        cur.close()
        conn.close()

def init_common_db():
    with get_db_cursor() as cur:
        cur.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id SERIAL PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                hashed_password TEXT NOT NULL,
                role TEXT NOT NULL,
                related_id INTEGER,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
        """)
        cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS is_blocked BOOLEAN NOT NULL DEFAULT FALSE")
        cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS telegram_chat_id TEXT")
        # One (role, related_id) pair = one account. Admins use related_id IS NULL,
        # which CREATE UNIQUE … WHERE excludes. Without this two shop accounts could
        # both point at the same shop_id and receive each other's Telegram messages.
        cur.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_users_role_related
            ON users (role, related_id)
            WHERE related_id IS NOT NULL
        """)
        cur.execute("""
            CREATE TABLE IF NOT EXISTS telegram_link_tokens (
                id SERIAL PRIMARY KEY,
                token TEXT UNIQUE NOT NULL,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
        """)
        # At most one outstanding link token per user — backs the UPSERT in
        # init_telegram_link so concurrent /init-link calls can't trample each other.
        cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_telegram_link_tokens_user ON telegram_link_tokens (user_id)")
        cur.execute("""
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
        # tickets-dependent DDL is in init_ticket_extensions(), called after needy init_db

def log_action(actor_username, action, target_type=None, target_id=None, details=None):
    try:
        with get_db_cursor() as cur:
            cur.execute(
                "INSERT INTO audit_log (actor_username, action, target_type, target_id, details) VALUES (%s, %s, %s, %s, %s)",
                (actor_username, action, target_type, target_id, details),
            )
    except Exception:
        pass

def init_ticket_extensions():
    """Run after needy init_db() so the tickets table exists."""
    with get_db_cursor() as cur:
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS assigned_volunteer_id INTEGER")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo TEXT")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_tickets_assigned_volunteer_id ON tickets (assigned_volunteer_id) WHERE assigned_volunteer_id IS NOT NULL")
        cur.execute("""
            CREATE TABLE IF NOT EXISTS delivery_ratings (
                ticket_id INTEGER PRIMARY KEY REFERENCES tickets(id) ON DELETE CASCADE,
                volunteer_id INTEGER,
                rating SMALLINT NOT NULL,
                comment TEXT,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
        """)
        cur.execute("CREATE INDEX IF NOT EXISTS idx_delivery_ratings_volunteer ON delivery_ratings (volunteer_id)")

def create_user(username, hashed_password, role, related_id=None):
    with get_db_cursor() as cur:
        cur.execute(
            "INSERT INTO users (username, hashed_password, role, related_id) VALUES (%s, %s, %s, %s) RETURNING id",
            (username, hashed_password, role, related_id)
        )
        return cur.fetchone()['id']
