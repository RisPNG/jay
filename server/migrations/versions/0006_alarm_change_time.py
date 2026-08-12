from alembic import op

revision = "0006_alarm_change_time"
down_revision = "0005_play_entitlements"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE changes
        ADD COLUMN entity_time bigint CHECK (entity_time IS NULL OR entity_time >= 0)
        """
    )


def downgrade() -> None:
    op.execute("ALTER TABLE changes DROP COLUMN entity_time")
