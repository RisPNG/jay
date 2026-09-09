import uuid

from django.contrib.postgres.fields import ArrayField
from django.db import models

from .common import SavedState, SoundMode


class SharedTimer(SavedState):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey("Group", on_delete=models.CASCADE, related_name="timers")
    label = models.CharField(max_length=120, null=True)
    label_color = models.IntegerField(default=-1)
    duration_seconds = models.PositiveIntegerField()
    increment_seconds = models.PositiveIntegerField()
    expires_at = models.DateTimeField()
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
        ]
        indexes = [models.Index(fields=["expires_at"], condition=models.Q(deleted_at=None), name="timer_expiry_idx")]
