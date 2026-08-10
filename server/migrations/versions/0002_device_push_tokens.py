from alembic import op

revision = "0002_device_push_tokens"
down_revision = "0001_social_alarms"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE devices ADD COLUMN push_token text")
    op.execute(
        "CREATE UNIQUE INDEX devices_push_token_idx ON devices(push_token) WHERE push_token IS NOT NULL"
    )


def downgrade() -> None:
    op.execute("DROP INDEX devices_push_token_idx")
    op.execute("ALTER TABLE devices DROP COLUMN push_token")
