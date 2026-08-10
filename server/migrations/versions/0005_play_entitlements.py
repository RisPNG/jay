from alembic import op

revision = "0005_play_entitlements"
down_revision = "0004_alarm_change_notifications"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE devices
        ADD COLUMN play_entitlement_verified_at timestamptz,
        ADD COLUMN play_entitlement_expires_at timestamptz
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE devices
        DROP COLUMN play_entitlement_expires_at,
        DROP COLUMN play_entitlement_verified_at
        """
    )
