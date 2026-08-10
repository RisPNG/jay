from alembic import op

revision = "0003_group_notification_defaults"
down_revision = "0002_device_push_tokens"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE groups ALTER COLUMN notify_snoozed SET DEFAULT true")
    op.execute("ALTER TABLE groups ALTER COLUMN notify_dismissed SET DEFAULT true")


def downgrade() -> None:
    op.execute("ALTER TABLE groups ALTER COLUMN notify_snoozed SET DEFAULT false")
    op.execute("ALTER TABLE groups ALTER COLUMN notify_dismissed SET DEFAULT true")
