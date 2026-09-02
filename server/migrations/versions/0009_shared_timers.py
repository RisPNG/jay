from alembic import op

revision = "0009_shared_timers"
down_revision = "0008_alarm_recurrence"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE shared_timers (
            id uuid PRIMARY KEY,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            label text,
            duration_seconds integer NOT NULL,
            increment_seconds integer NOT NULL,
            expires_at timestamp with time zone NOT NULL,
            started_by text NOT NULL REFERENCES devices(id),
            created_at timestamp with time zone NOT NULL DEFAULT now()
        );

        CREATE INDEX shared_timers_group_id_idx ON shared_timers (group_id);
        """
    )


def downgrade() -> None:
    op.execute("DROP TABLE shared_timers")
