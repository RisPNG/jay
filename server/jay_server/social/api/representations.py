from rest_framework import serializers
from drf_spectacular.utils import PolymorphicProxySerializer, extend_schema_field

from ..models import AlarmActivity, AlarmOccurrence, Group, GroupActivity, GroupMembership, Identity, SharedAlarm, SharedSound, SharedTimer


class IdentityRepresentation(serializers.ModelSerializer):
    class Meta:
        model = Identity
        fields = ["id", "name", "time_zone", "saved_at", "save_id"]
        read_only_fields = fields


class GroupRepresentation(serializers.ModelSerializer):
    class Meta:
        model = Group
        fields = ["id", "name", "alarm_permission", "notify_alarm_changes", "notify_snoozed", "notify_dismissed", "notify_ignored", "alarm_time_basis", "alarm_time_zone", "shared_answers", "saved_at", "save_id"]
        read_only_fields = fields


class MembershipRepresentation(serializers.ModelSerializer):
    name = serializers.CharField(source="identity.name")

    class Meta:
        model = GroupMembership
        fields = ["id", "group_id", "identity_id", "name", "role", "joined_at", "saved_at", "save_id"]
        read_only_fields = fields


class MembershipAccessRepresentation(serializers.ModelSerializer):
    scope_id = serializers.UUIDField(source="group.scope_id")

    class Meta:
        model = GroupMembership
        fields = ["id", "group_id", "scope_id", "role", "notify_membership", "notify_administrative", "preferences_saved_at", "preferences_save_id"]
        read_only_fields = fields


class SoundRepresentation(serializers.ModelSerializer):
    class Meta:
        model = SharedSound
        fields = ["id", "group_id", "title", "sha256", "byte_length", "duration_ms", "status", "failure_code", "ready_at"]
        read_only_fields = fields


class AlarmRepresentation(serializers.ModelSerializer):
    class Meta:
        model = SharedAlarm
        fields = ["id", "group_id", "revision", "local_time_ms", "label", "label_color", "enabled", "days", "vibrate", "start_date", "repeat_interval", "repeat_unit", "repeat_anchor", "repeat_duration", "repeat_duration_unit", "end_date", "end_occurrences", "advanced", "snooze_enabled", "snooze_minutes", "vibration_pattern", "vibration_pattern_name", "sound_mode", "sound_id", "saved_at", "save_id"]
        read_only_fields = fields


class TimerRepresentation(serializers.ModelSerializer):
    class Meta:
        model = SharedTimer
        fields = ["id", "group_id", "label", "label_color", "duration_seconds", "increment_seconds", "expires_at", "vibrate", "vibration_pattern", "vibration_pattern_name", "sound_mode", "sound_id", "saved_at", "save_id"]
        read_only_fields = fields


class OccurrenceRepresentation(serializers.ModelSerializer):
    class Meta:
        model = AlarmOccurrence
        fields = ["id", "alarm_id", "group_id", "identity_id", "alarm_revision", "occurrence_key", "cycle_date", "trigger_at", "deadline_at", "state", "resolved_at"]
        read_only_fields = fields


class ActivityRepresentation(serializers.ModelSerializer):
    class Meta:
        model = GroupActivity
        fields = ["id", "group_id", "revision", "ordinal", "entity_type", "entity_id", "action", "actor_id", "subject_id", "recipient_id", "group_label", "actor_label", "subject_label", "entity_label", "entity_time", "details", "occurred_at"]
        read_only_fields = fields


class OutcomeRepresentation(serializers.ModelSerializer):
    class Meta:
        model = AlarmActivity
        fields = ["id", "alarm_id", "group_id", "identity_id", "alarm_revision", "occurrence_key", "kind", "occurred_at", "reason"]
        read_only_fields = fields


class CapabilitiesRepresentation(serializers.Serializer):
    shared_sound_upload = serializers.BooleanField()
    requires_play_entitlement = serializers.BooleanField()
    expires_at = serializers.DateTimeField(allow_null=True)


class DeletedResourceRepresentation(serializers.Serializer):
    id = serializers.CharField()


@extend_schema_field(PolymorphicProxySerializer(
    component_name="SynchronizedResource",
    serializers=[IdentityRepresentation, CapabilitiesRepresentation, MembershipAccessRepresentation, GroupRepresentation, MembershipRepresentation, AlarmRepresentation, TimerRepresentation, SoundRepresentation, OccurrenceRepresentation, OutcomeRepresentation, ActivityRepresentation, DeletedResourceRepresentation],
    resource_type_field_name=None,
))
class SynchronizedResourceField(serializers.JSONField):
    pass


class SyncItemRepresentation(serializers.Serializer):
    kind = serializers.ChoiceField(choices=["identity", "capabilities", "membership", "group", "member", "alarm", "timer", "sound", "occurrence", "outcome", "activity"])
    key = serializers.CharField()
    action = serializers.CharField()
    data = SynchronizedResourceField()
    revision = serializers.IntegerField(min_value=0)
    ordinal = serializers.IntegerField(min_value=0)


class ScopeSyncRepresentation(serializers.Serializer):
    scope_id = serializers.UUIDField()
    mode = serializers.ChoiceField(choices=["snapshot", "delta"])
    from_revision = serializers.IntegerField(min_value=0)
    through_revision = serializers.IntegerField(min_value=0)
    next_cursor = serializers.CharField()
    has_more = serializers.BooleanField()
    items = SyncItemRepresentation(many=True)
    server_time = serializers.DateTimeField()


class ActivityPageRepresentation(serializers.Serializer):
    items = ActivityRepresentation(many=True)
    next_before = serializers.CharField(allow_null=True)


class ErrorRepresentation(serializers.Serializer):
    code = serializers.CharField()
    detail = serializers.CharField()
    errors = serializers.ListField(child=serializers.DictField())
    current = serializers.JSONField(required=False)
