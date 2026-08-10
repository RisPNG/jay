from alembic import op

revision = "0004_alarm_change_notifications"
down_revision = "0003_group_notification_defaults"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE changes
        ADD COLUMN actor_device_id text REFERENCES devices(id) ON DELETE SET NULL,
        ADD COLUMN action text CHECK (
            action IS NULL OR action IN ('created', 'edited', 'enabled', 'disabled', 'deleted')
        ),
        ADD COLUMN entity_label text
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE changes
        DROP COLUMN entity_label,
        DROP COLUMN action,
        DROP COLUMN actor_device_id
        """
    )
