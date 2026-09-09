import uuid

from django.db import models
from django.utils import timezone


class SharedSound(models.Model):
    class State(models.TextChoices):
        PENDING = "pending"
        VERIFYING = "verifying"
        READY = "ready"
        FAILED = "failed"
        DELETING = "deleting"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey("Group", on_delete=models.CASCADE)
    uploaded_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL)
    title = models.CharField(max_length=200)
    sha256 = models.CharField(max_length=64, null=True)
    byte_length = models.PositiveIntegerField(null=True)
    duration_ms = models.PositiveIntegerField(null=True)
    object_key = models.CharField(max_length=256, unique=True)
    staging_key = models.CharField(max_length=256, unique=True)
    status = models.CharField(max_length=16, choices=State, default=State.PENDING)
    failure_code = models.CharField(max_length=64, null=True)
    created_at = models.DateTimeField(default=timezone.now)
    ready_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [
            models.CheckConstraint(condition=models.Q(byte_length__range=(1, 33554432)), name="sound_bytes_valid"),
            models.CheckConstraint(condition=models.Q(duration_ms__range=(1, 300000)), name="sound_duration_valid"),
            models.CheckConstraint(condition=models.Q(sha256__regex=r"^[a-f0-9]{64}$"), name="sound_hash_valid"),
            models.CheckConstraint(condition=models.Q(status__in=["pending", "verifying", "ready", "failed", "deleting"]), name="sound_status_valid"),
        ]
