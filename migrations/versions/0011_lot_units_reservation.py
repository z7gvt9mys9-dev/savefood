"""lot units and reservation

Revision ID: 0011
Revises: 0010
Create Date: 2026-06-12 10:00:00.000000

"""
from alembic import op
import sqlalchemy as sa

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None

def upgrade():
    # 1. Update lots table
    op.add_column('lots', sa.Column('unit', sa.String(), nullable=False, server_default='кг'))
    op.add_column('lots', sa.Column('unit_weight_kg', sa.Float(), nullable=False, server_default='1.0'))
    op.add_column('lots', sa.Column('initial_quantity', sa.Float()))
    
    # Backfill initial_quantity from existing quantity
    op.execute("UPDATE lots SET initial_quantity = quantity, quantity = CAST(quantity AS float)")
    # Change quantity type to Float for partial kg if needed later, 
    # but for now we keep it compatible. Actually, let's make it float to be safe.
    op.execute("ALTER TABLE lots ALTER COLUMN quantity TYPE float")

    # 2. Update tickets table
    op.add_column('tickets', sa.Column('quantity', sa.Float(), nullable=False, server_default='1.0'))
    op.add_column('tickets', sa.Column('expires_at', sa.DateTime(timezone=True)))

def downgrade():
    op.drop_column('tickets', 'expires_at')
    op.drop_column('tickets', 'quantity')
    op.drop_column('lots', 'initial_quantity')
    op.drop_column('lots', 'unit_weight_kg')
    op.drop_column('lots', 'unit')
