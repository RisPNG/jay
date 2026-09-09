import uuid

from django.db import models
from django.utils import timezone

from .common import AlarmPermission, AlarmTimeBasis, MemberRole, SavedState


class Group(SavedState):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    scope = models.OneToOneField("SyncScope", on_delete=models.PROTECT, related_name="group")
    name = models.CharField(max_length=80)
    alarm_permission = models.CharField(max_length=16, choices=AlarmPermission, default=AlarmPermission.EVERYONE)
    notify_alarm_changes = models.BooleanField(default=True)
    notify_snoozed = models.BooleanField(default=True)
    notify_dismissed = models.BooleanField(default=True)
    notify_ignored = models.BooleanField(default=True)
    alarm_time_basis = models.CharField(max_length=24, choices=AlarmTimeBasis, default=AlarmTimeBasis.MEMBER_LOCAL)
    alarm_time_zone = models.CharField(max_length=100, default="UTC")
    shared_answers = models.BooleanField(default=False)
    created_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL)
    deleted_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [
            models.CheckConstraint(condition=~models.Q(name=""), name="group_name_nonempty"),
            models.CheckConstraint(condition=models.Q(alarm_permission__in=AlarmPermission.values), name="group_permission_valid"),
            models.CheckConstraint(condition=models.Q(alarm_time_basis__in=AlarmTimeBasis.values), name="group_time_basis_valid"),
        ]


class GroupMembership(SavedState):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey(Group, on_delete=models.CASCADE, related_name="memberships")
    identity = models.ForeignKey("Identity", on_delete=models.CASCADE, related_name="memberships")
    role = models.CharField(max_length=16, choices=MemberRole, default=MemberRole.MEMBER)
    notify_membership = models.BooleanField(default=True)
    notify_administrative = models.BooleanField(default=True)
    preferences_saved_at = models.DateTimeField(default=timezone.now)
    preferences_save_id = models.UUIDField(default=uuid.uuid4)
    joined_at = models.DateTimeField(default=timezone.now)
    removed_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["group", "identity"], condition=models.Q(removed_at=None), name="membership_active_unique"),
            models.CheckConstraint(condition=models.Q(role__in=MemberRole.values), name="membership_role_valid"),
        ]


class GroupInvitation(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4)
    group = models.ForeignKey(Group, on_delete=models.CASCADE)
    token_hash = models.BinaryField(unique=True)
    created_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="invitations_created")
    expires_at = models.DateTimeField()
    consumed_by = models.ForeignKey("Identity", null=True, on_delete=models.SET_NULL, related_name="invitations_consumed")
    consumed_at = models.DateTimeField(null=True)
    created_at = models.DateTimeField(default=timezone.now)
