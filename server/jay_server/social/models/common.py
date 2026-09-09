import uuid

from django.db import models
from django.utils import timezone


class SavedState(models.Model):
    saved_at = models.DateTimeField(default=timezone.now)
    save_id = models.UUIDField(default=uuid.uuid4)
    created_at = models.DateTimeField(default=timezone.now)
    updated_at = models.DateTimeField(default=timezone.now)

    class Meta:
        abstract = True


class MemberRole(models.TextChoices):
    MEMBER = "member"
    LEADER = "leader"


class AlarmPermission(models.TextChoices):
    EVERYONE = "everyone"
    LEADERS = "leaders"


class AlarmTimeBasis(models.TextChoices):
    MEMBER_LOCAL = "member_local"
    GROUP_TIME_ZONE = "group_time_zone"


class SoundMode(models.TextChoices):
    OFF = "off"
    MEMBER_DEFAULT = "member_default"
    SHARED = "shared"


class ActivityKind(models.TextChoices):
    SNOOZED = "snoozed"
    DISMISSED = "dismissed"
    IGNORED = "ignored"


class RepeatUnit(models.TextChoices):
    DAY = "DAY"
    WEEK = "WEEK"
    MONTH = "MONTH"
    YEAR = "YEAR"


class RepeatAnchor(models.TextChoices):
    DAY_OF_MONTH = "DAY_OF_MONTH"
    DAY_OF_WEEK = "DAY_OF_WEEK"
