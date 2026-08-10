from datetime import datetime
from enum import StrEnum
from uuid import UUID

from pydantic import BaseModel, Field, field_validator


class AlarmPermission(StrEnum):
    EVERYONE = "everyone"
    LEADERS = "leaders"


class MemberRole(StrEnum):
    MEMBER = "member"
    LEADER = "leader"


class ActivityKind(StrEnum):
    SNOOZED = "snoozed"
    DISMISSED = "dismissed"


class DeviceRegistration(BaseModel):
    id: str = Field(pattern=r"^[a-f0-9]{64}$")
    name: str = Field(min_length=1, max_length=64)
    token: str = Field(min_length=32, max_length=256)


class DeviceUpdate(BaseModel):
    name: str = Field(min_length=1, max_length=64)


class PushTokenUpdate(BaseModel):
    token: str = Field(min_length=1, max_length=4096)


class GroupCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    alarm_permission: AlarmPermission = AlarmPermission.EVERYONE
    notify_snoozed: bool = True
    notify_dismissed: bool = True


class GroupUpdate(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    alarm_permission: AlarmPermission
    notify_snoozed: bool
    notify_dismissed: bool


class InviteCreate(BaseModel):
    expires_in_hours: int | None = Field(default=None, ge=1, le=720)


class InviteJoin(BaseModel):
    token: str = Field(min_length=32, max_length=256)


class MemberUpdate(BaseModel):
    role: MemberRole


class SharedAlarmPayload(BaseModel):
    time: int = Field(ge=0)
    label: str | None = Field(default=None, max_length=200)
    enabled: bool
    days: list[int]
    vibrate: bool
    repeat: bool
    snooze_enabled: bool
    snooze_minutes: int = Field(gt=0, le=1440)
    sound_enabled: bool
    vibration_pattern: list[int]
    vibration_pattern_name: str = Field(min_length=1, max_length=80)

    @field_validator("days")
    @classmethod
    def validate_days(cls, days: list[int]) -> list[int]:
        if len(days) != len(set(days)) or any(day not in range(7) for day in days):
            raise ValueError("days must contain unique values from 0 through 6")
        return days

    @field_validator("vibration_pattern")
    @classmethod
    def validate_vibration_pattern(cls, pattern: list[int]) -> list[int]:
        if not pattern or any(duration < 0 for duration in pattern):
            raise ValueError("vibration_pattern must contain non-negative durations")
        return pattern


class SharedAlarmCreate(SharedAlarmPayload):
    group_id: UUID


class SharedAlarmUpdate(SharedAlarmPayload):
    expected_revision: int = Field(gt=0)


class SharedAlarmDelete(BaseModel):
    expected_revision: int = Field(gt=0)


class AlarmActivityCreate(BaseModel):
    alarm_revision: int = Field(gt=0)
    kind: ActivityKind
    occurred_at: datetime
