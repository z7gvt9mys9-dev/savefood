"""SaaS plans, OCR receipts, Auto-KYC columns

Revision ID: 0003
Revises: 0002
Create Date: 2026-06-11 00:00:00.000000

Commercialization v2.0 schema:
- shops.plan — SaaS tariff ('basic' | 'pro' | 'enterprise'), gated in backend/billing.py
- receipts — OCR-parsed write-off receipts with anti-fraud verdict (backend/receipt_service.py)
- needy.kyc_* — Auto-KYC v1 AI pre-check verdict for the moderation queue (backend/kyc_service.py)

Kept in sync with `init_db()` in backend/shop/db.py and backend/needy/db.py.
"""
from typing import Sequence, Union
from alembic import op

revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("ALTER TABLE shops ADD COLUMN IF NOT EXISTS plan TEXT NOT NULL DEFAULT 'basic'")

    op.execute("""
        CREATE TABLE IF NOT EXISTS receipts (
            id SERIAL PRIMARY KEY,
            shop_id INTEGER NOT NULL REFERENCES shops(id),
            photo TEXT,
            sha256 TEXT,
            fingerprint TEXT,
            merchant TEXT,
            receipt_date DATE,
            total REAL,
            currency TEXT,
            items TEXT,
            fraud_score REAL,
            fraud_reasons TEXT,
            status TEXT NOT NULL DEFAULT 'parsed',
            lot_ids TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            confirmed_at TIMESTAMP WITH TIME ZONE
        )
    """)
    op.execute("CREATE INDEX IF NOT EXISTS idx_receipts_shop ON receipts (shop_id)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_receipts_sha ON receipts (sha256)")
    op.execute("CREATE INDEX IF NOT EXISTS idx_receipts_fp ON receipts (fingerprint) WHERE fingerprint IS NOT NULL")

    op.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_score REAL")
    op.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_verdict TEXT")
    op.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_notes TEXT")
    op.execute("ALTER TABLE needy ADD COLUMN IF NOT EXISTS kyc_checked_at TIMESTAMP WITH TIME ZONE")


def downgrade() -> None:
    op.execute("ALTER TABLE needy DROP COLUMN IF EXISTS kyc_checked_at")
    op.execute("ALTER TABLE needy DROP COLUMN IF EXISTS kyc_notes")
    op.execute("ALTER TABLE needy DROP COLUMN IF EXISTS kyc_verdict")
    op.execute("ALTER TABLE needy DROP COLUMN IF EXISTS kyc_score")
    op.execute("DROP TABLE IF EXISTS receipts CASCADE")
    op.execute("ALTER TABLE shops DROP COLUMN IF EXISTS plan")
