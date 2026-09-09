import uuid

from django.db import models
from django.utils import timezone


class SyncScope(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    head_revision = models.PositiveBigIntegerField(default=0)
    retention_floor = models.PositiveBigIntegerField(default=0)


class SyncVersion(models.Model):
    scope = models.ForeignKey(SyncScope, on_delete=models.CASCADE, related_name="versions")
    revision = models.PositiveBigIntegerField()
    ordinal = models.PositiveIntegerField()
    kind = models.CharField(max_length=32)
    key = models.CharField(max_length=200)
    action = models.CharField(max_length=32)
    representation = models.JSONField()
    recipient = models.ForeignKey("Identity", null=True, on_delete=models.CASCADE)
    created_at = models.DateTimeField(default=timezone.now)
    superseded_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["scope", "revision", "ordinal"], name="sync_revision_ordinal_unique")]
        indexes = [models.Index(fields=["scope", "kind", "key", "-revision"], name="sync_resource_version_idx")]


class GroupActivity(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey("Group", on_delete=models.CASCADE)
    revision = models.PositiveBigIntegerField()
    ordinal = models.PositiveIntegerField()
    entity_type = models.CharField(max_length=32)
    entity_id = models.CharField(max_length=200)
    action = models.CharField(max_length=32)
    actor = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="group_activity")
    subject = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="subject_activity")
    recipient = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="received_activity")
    group_label = models.CharField(max_length=80)
    actor_label = models.CharField(max_length=64, null=True)
    subject_label = models.CharField(max_length=64, null=True)
    entity_label = models.CharField(max_length=200, null=True)
    entity_time = models.PositiveIntegerField(null=True)
    details = models.JSONField(null=True)
    occurred_at = models.DateTimeField(default=timezone.now)

    class Meta:
        indexes = [models.Index(fields=["group", "-revision", "-ordinal"], name="group_activity_cursor_idx")]


class OperationReceipt(models.Model):
    identity = models.ForeignKey("Identity", on_delete=models.CASCADE)
    operation_id = models.UUIDField()
    fingerprint = models.CharField(max_length=64)
    status = models.PositiveSmallIntegerField(null=True)
    response = models.JSONField(null=True)
    completed_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["identity", "operation_id"], name="operation_identity_unique")]


class DeliveryWork(models.Model):
    class Kind(models.TextChoices):
        PUSH = "push"
        RESCHEDULE = "reschedule"
        VERIFY = "verify"
        DELETE_SOUND = "delete_sound"
        RETIRE = "retire"
        DELETE_GROUP = "delete_group"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    kind = models.CharField(max_length=20, choices=Kind)
    deduplication_key = models.CharField(max_length=300, unique=True)
    payload = models.JSONField(default=dict)
    available_at = models.DateTimeField(default=timezone.now)
    created_at = models.DateTimeField(default=timezone.now)
    attempts = models.PositiveIntegerField(default=0)
    lease_owner = models.UUIDField(null=True)
    lease_until = models.DateTimeField(null=True)
    failed_at = models.DateTimeField(null=True)
    error_code = models.CharField(max_length=100, null=True)

    class Meta:
        indexes = [models.Index(fields=["kind", "available_at"], condition=models.Q(failed_at=None), name="work_ready_idx")]


class WorkerHeartbeat(models.Model):
    instance_id = models.UUIDField()
    work_class = models.CharField(max_length=20)
    progressed_at = models.DateTimeField(default=timezone.now)
    error_code = models.CharField(max_length=100, null=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=["instance_id", "work_class"], name="worker_class_unique")]


class DeletedResource(models.Model):
    id = models.UUIDField(primary_key=True)
    kind = models.CharField(max_length=32)
    deleted_at = models.DateTimeField(default=timezone.now)
