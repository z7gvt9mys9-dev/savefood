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
        # Social login identities (oauth_routes.py). One provider identity can
        # belong to exactly one account — enforced by partial unique indexes.
        cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS google_id TEXT")
        cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS yandex_id TEXT")
        cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_users_google_id ON users (google_id) WHERE google_id IS NOT NULL")
        cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_users_yandex_id ON users (yandex_id) WHERE yandex_id IS NOT NULL")
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
        # Login-via-Telegram tokens: created anonymous (user_id NULL), the bot
        # fills user_id when the owner of a linked chat opens /start login_<token>.
        cur.execute("""
            CREATE TABLE IF NOT EXISTS telegram_login_tokens (
                id SERIAL PRIMARY KEY,
                token TEXT UNIQUE NOT NULL,
                user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
        """)
        # Web Push (VAPID) subscriptions — one user can have several browsers.
        # Dead endpoints (404/410 on send) are pruned by push_service.
        cur.execute("""
            CREATE TABLE IF NOT EXISTS push_subscriptions (
                id SERIAL PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                endpoint TEXT UNIQUE NOT NULL,
                p256dh TEXT NOT NULL,
                auth TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
        """)
        cur.execute("CREATE INDEX IF NOT EXISTS idx_push_subscriptions_user ON push_subscriptions (user_id)")
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
        # Pre-publication moderation of the public Impact feed photo (§36).
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_status TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_ai_verdict TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_ai_score REAL")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_ai_notes TEXT")
        cur.execute("ALTER TABLE tickets ADD COLUMN IF NOT EXISTS delivery_photo_reviewed_at TIMESTAMP WITH TIME ZONE")
        # Photos that pre-date moderation are already public — grandfather them.
        cur.execute("UPDATE tickets SET delivery_photo_status = 'approved' "
                    "WHERE delivery_photo IS NOT NULL AND delivery_photo_status IS NULL")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_tickets_photo_status ON tickets (delivery_photo_status) WHERE delivery_photo_status IS NOT NULL")
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
