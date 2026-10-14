import uuid

from django.contrib.postgres.fields import ArrayField
from django.db import models

from .common import SavedState, SoundMode


class SharedTimer(SavedState):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey("Group", on_delete=models.CASCADE, related_name="timers")
    label = models.CharField(max_length=120, null=True)
    label_color = models.IntegerField(default=0)
    duration_seconds = models.PositiveIntegerField()
    increment_seconds = models.PositiveIntegerField()
    expires_at = models.DateTimeField()
    run_id = models.UUIDField(default=uuid.uuid4)
    timeout_minutes = models.PositiveSmallIntegerField(default=5)
    repeat_enabled = models.BooleanField(default=False)
    repeat_count = models.PositiveSmallIntegerField(default=0)
    repeat_index = models.PositiveIntegerField(default=0)
    vibrate = models.BooleanField(default=True)
    vibration_pattern = ArrayField(models.PositiveIntegerField(), size=256)
    vibration_pattern_name = models.CharField(max_length=80)
    sound_mode = models.CharField(max_length=16, choices=SoundMode, default=SoundMode.MEMBER_DEFAULT)
    sound = models.ForeignKey("SharedSound", null=True, on_delete=models.SET_NULL, related_name="timers")
    started_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL)
    deleted_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [
            models.CheckConstraint(condition=models.Q(duration_seconds__range=(1, 86400)), name="timer_duration_valid"),
            models.CheckConstraint(condition=models.Q(increment_seconds__range=(1, 3600)), name="timer_increment_valid"),
            models.CheckConstraint(condition=models.Q(sound_mode__in=SoundMode.values), name="timer_sound_mode_valid"),
            models.CheckConstraint(condition=models.Q(timeout_minutes__range=(1, 120)), name="timer_timeout_valid"),
            models.CheckConstraint(condition=models.Q(repeat_count__lte=100), name="timer_repeat_count_valid"),
            models.CheckConstraint(condition=models.Q(repeat_enabled=False, repeat_index=0) | models.Q(repeat_enabled=True, repeat_count=0) | models.Q(repeat_enabled=True, repeat_index__lte=models.F("repeat_count")), name="timer_repeat_index_valid"),
        ]
        indexes = [models.Index(fields=["expires_at"], condition=models.Q(deleted_at=None), name="timer_expiry_idx")]


class TimerOccurrence(models.Model):
    class State(models.TextChoices):
        DISMISSED = "dismissed"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    timer = models.ForeignKey(SharedTimer, on_delete=models.CASCADE, related_name="occurrences")
    group = models.ForeignKey("Group", on_delete=models.CASCADE)
    identity = models.ForeignKey("Identity", on_delete=models.CASCADE)
    membership = models.ForeignKey("GroupMembership", on_delete=models.CASCADE)
    run_id = models.UUIDField()
    repeat_index = models.PositiveIntegerField()
    trigger_at = models.DateTimeField()
    deadline_at = models.DateTimeField()
    state = models.CharField(max_length=16, choices=State, default=State.DISMISSED)
    resolved_at = models.DateTimeField()

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["timer", "membership", "run_id", "repeat_index"], name="timer_occurrence_member_unique"),
            models.CheckConstraint(condition=models.Q(deadline_at__gt=models.F("trigger_at")), name="timer_occurrence_deadline_valid"),
            models.CheckConstraint(condition=models.Q(state="dismissed"), name="timer_occurrence_state_valid"),
        ]
        indexes = [models.Index(fields=["resolved_at"], name="timer_occurrence_resolved_idx")]
