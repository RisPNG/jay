import json
from uuid import UUID

from fastapi import HTTPException, status
from psycopg import Connection


def require_group_member(connection: Connection, group_id: UUID, device_id: str) -> dict:
    membership = connection.execute(
        """
        SELECT g.*, gm.role
        FROM groups g
        JOIN group_members gm ON gm.group_id = g.id
        WHERE g.id = %s AND gm.device_id = %s
        """,
        (group_id, device_id),
    ).fetchone()
    if membership is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Group not found")
    return membership


def require_group_leader(connection: Connection, group_id: UUID, device_id: str) -> dict:
    membership = require_group_member(connection, group_id, device_id)
    if membership["role"] != "leader":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "A group leader is required")
    return membership


def require_alarm_editor(connection: Connection, group_id: UUID, device_id: str) -> dict:
    membership = require_group_member(connection, group_id, device_id)
    if membership["alarm_permission"] == "leaders" and membership["role"] != "leader":
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only group leaders may change alarms")
    return membership


def record_group_change(
    connection: Connection, group_id: UUID, entity_type: str, entity_id: str
) -> None:
    connection.execute(
        "INSERT INTO changes (group_id, entity_type, entity_id) VALUES (%s, %s, %s)",
        (group_id, entity_type, entity_id),
    )
    connection.execute(
        "SELECT pg_notify('jay_changes', %s)",
        (
            json.dumps(
                {
                    "group_id": str(group_id),
                    "entity_type": entity_type,
                    "entity_id": entity_id,
                }
            ),
        ),
    )


def get_group_push_tokens(
    connection: Connection, group_id: UUID, excluding_device_id: str
) -> list[str]:
    return [
        row["push_token"]
        for row in connection.execute(
            """
            SELECT d.push_token
            FROM group_members gm
            JOIN devices d ON d.id = gm.device_id
            WHERE gm.group_id = %s AND gm.device_id != %s AND d.push_token IS NOT NULL
            """,
            (group_id, excluding_device_id),
        ).fetchall()
    ]
