import uuid

from django.contrib.postgres.fields import ArrayField
from django.db import models
from django.utils import timezone

from .common import ActivityKind, RepeatAnchor, RepeatUnit, SavedState, SoundMode


class SharedAlarm(SavedState):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey("Group", on_delete=models.CASCADE, related_name="alarms")
    revision = models.PositiveIntegerField(default=1)
    local_time_ms = models.PositiveIntegerField()
    label = models.CharField(max_length=200, null=True)
    label_color = models.IntegerField(default=-1)
    enabled = models.BooleanField()
    days = ArrayField(models.PositiveSmallIntegerField(), size=7)
    vibrate = models.BooleanField()
    start_date = models.DateField()
    repeat_interval = models.PositiveSmallIntegerField()
    repeat_unit = models.CharField(max_length=8, choices=RepeatUnit)
    repeat_anchor = models.CharField(max_length=16, choices=RepeatAnchor)
    repeat_duration = models.PositiveSmallIntegerField(null=True)
    repeat_duration_unit = models.CharField(max_length=8, choices=RepeatUnit)
    end_date = models.DateField(null=True)
    end_occurrences = models.PositiveSmallIntegerField(null=True)
    advanced = models.BooleanField(default=False)
    snooze_enabled = models.BooleanField()
    snooze_minutes = models.PositiveSmallIntegerField()
    vibration_pattern = ArrayField(models.PositiveIntegerField(), size=256)
    vibration_pattern_name = models.CharField(max_length=80)
    sound_mode = models.CharField(max_length=16, choices=SoundMode, default=SoundMode.MEMBER_DEFAULT)
    sound = models.ForeignKey("SharedSound", null=True, on_delete=models.SET_NULL, related_name="alarms")
    created_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="alarms_created")
    updated_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="alarms_updated")
    deleted_at = models.DateTimeField(null=True)
    inactive_cycle_streak = models.PositiveSmallIntegerField(default=0)
    last_evaluated_cycle_date = models.DateField(null=True)

    class Meta:
        constraints = [
            models.CheckConstraint(condition=models.Q(revision__gte=1), name="alarm_revision_positive"),
            models.CheckConstraint(condition=models.Q(local_time_ms__lt=86400000), name="alarm_time_within_day"),
            models.CheckConstraint(condition=models.Q(repeat_interval__range=(1, 999)), name="alarm_repeat_interval_valid"),
            models.CheckConstraint(condition=models.Q(repeat_duration=None) | models.Q(repeat_duration__range=(1, 999)), name="alarm_repeat_duration_valid"),
            models.CheckConstraint(condition=models.Q(end_occurrences=None) | models.Q(end_occurrences__range=(1, 999)), name="alarm_end_count_valid"),
            models.CheckConstraint(condition=models.Q(snooze_minutes__range=(1, 1440)), name="alarm_snooze_valid"),
            models.CheckConstraint(condition=models.Q(days__contained_by=[0, 1, 2, 3, 4, 5, 6]), name="alarm_days_valid"),
            models.CheckConstraint(condition=models.Q(repeat_unit__in=RepeatUnit.values), name="alarm_repeat_unit_valid"),
            models.CheckConstraint(condition=models.Q(repeat_duration_unit__in=RepeatUnit.values), name="alarm_duration_unit_valid"),
            models.CheckConstraint(condition=models.Q(repeat_anchor__in=RepeatAnchor.values), name="alarm_anchor_valid"),
            models.CheckConstraint(condition=models.Q(sound_mode__in=SoundMode.values), name="alarm_sound_mode_valid"),
            models.CheckConstraint(condition=models.Q(end_date=None) | models.Q(end_date__gte=models.F("start_date")), name="alarm_date_range_valid"),
            models.CheckConstraint(condition=models.Q(inactive_cycle_streak__lte=3), name="alarm_inactivity_valid"),
        ]


class AlarmOccurrence(models.Model):
    class State(models.TextChoices):
        PENDING = "pending"
        SNOOZED = "snoozed"
        DISMISSED = "dismissed"
        IGNORED = "ignored"
        CANCELED = "canceled"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    alarm = models.ForeignKey(SharedAlarm, on_delete=models.CASCADE, related_name="occurrences")
    group = models.ForeignKey("Group", on_delete=models.CASCADE)
    identity = models.ForeignKey("Identity", on_delete=models.CASCADE)
    alarm_revision = models.PositiveIntegerField()
    occurrence_key = models.CharField(max_length=200)
    cycle_date = models.DateField()
    trigger_at = models.DateTimeField()
    deadline_at = models.DateTimeField()
    state = models.CharField(max_length=16, choices=State, default=State.PENDING)
    resolved_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["alarm", "identity", "occurrence_key"], name="occurrence_identity_key_unique"),
            models.CheckConstraint(condition=models.Q(alarm_revision__gte=1), name="occurrence_revision_positive"),
            models.CheckConstraint(condition=models.Q(deadline_at__gt=models.F("trigger_at")), name="occurrence_deadline_after_trigger"),
            models.CheckConstraint(condition=models.Q(state__in=["pending", "snoozed", "dismissed", "ignored", "canceled"]), name="occurrence_state_valid"),
        ]
        indexes = [
            models.Index(fields=["deadline_at", "group"], condition=models.Q(state="pending"), name="occurrence_due_idx"),
            models.Index(fields=["alarm", "alarm_revision", "cycle_date"], name="occurrence_cycle_idx"),
        ]


class AlarmDelivery(models.Model):
    alarm = models.ForeignKey(SharedAlarm, on_delete=models.CASCADE)
    identity = models.ForeignKey("Identity", on_delete=models.CASCADE)
    revision = models.PositiveIntegerField()
    received_at = models.DateTimeField(default=timezone.now)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["alarm", "identity", "revision"], name="delivery_revision_unique")]


class AlarmActivity(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    alarm = models.ForeignKey(SharedAlarm, on_delete=models.CASCADE, related_name="activities")
    group = models.ForeignKey("Group", on_delete=models.CASCADE)
    identity = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL)
    alarm_revision = models.PositiveIntegerField()
    occurrence_key = models.CharField(max_length=200, null=True)
    kind = models.CharField(max_length=16, choices=ActivityKind)
    occurred_at = models.DateTimeField()
    reason = models.CharField(max_length=40, null=True)
    request_fingerprint = models.CharField(max_length=64)
    created_at = models.DateTimeField(default=timezone.now)

    class Meta:
        indexes = [models.Index(fields=["alarm", "identity", "occurrence_key"], name="activity_outcome_idx")]
        constraints = [models.CheckConstraint(condition=models.Q(kind__in=ActivityKind.values), name="activity_kind_valid")]
