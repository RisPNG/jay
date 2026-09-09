from datetime import timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.utils import timezone
from django.utils.dateparse import parse_datetime
from rest_framework import serializers

from ..models import ActivityKind, AlarmPermission, AlarmTimeBasis, MemberRole, RepeatAnchor, RepeatUnit, SoundMode


class CommandSerializer(serializers.Serializer):
    def to_internal_value(self, data):
        if not isinstance(data, dict):
            raise serializers.ValidationError("An object is required")
        unknown = set(data) - set(self.fields)
        if unknown:
            raise serializers.ValidationError({name: "Unknown field" for name in sorted(unknown)})
        for name, field in self.fields.items():
            value = data.get(name)
            if value is None:
                continue
            if isinstance(field, serializers.BooleanField) and type(value) is not bool:
                raise serializers.ValidationError({name: "A boolean is required"})
            if isinstance(field, serializers.IntegerField) and type(value) is not int:
                raise serializers.ValidationError({name: "An integer is required"})
            if isinstance(field, serializers.ListField) and isinstance(field.child, serializers.IntegerField):
                if not isinstance(value, list) or any(type(item) is not int for item in value):
                    raise serializers.ValidationError({name: "An array of integers is required"})
            if isinstance(field, serializers.DateTimeField):
                try:
                    timestamp = parse_datetime(value) if isinstance(value, str) else None
                except ValueError:
                    timestamp = None
                if timestamp is None or timezone.is_naive(timestamp):
                    raise serializers.ValidationError({name: "An ISO 8601 timestamp with a UTC offset is required"})
        return super().to_internal_value(data)


class SavedCommand(CommandSerializer):
    saved_at = serializers.DateTimeField()

    def validate_saved_at(self, value):
        if value > timezone.now() + timedelta(minutes=2):
            from ..errors import DomainError
            raise DomainError("clock_invalid", "The saved time is ahead of the server clock")
        return value


class MembershipCommand(CommandSerializer):
    membership_id = serializers.UUIDField()


class MemberCommand(SavedCommand):
    membership_id = serializers.UUIDField()


class IdentityRegistration(CommandSerializer):
    id = serializers.RegexField(r"^[a-f0-9]{64}$")
    name = serializers.CharField(min_length=1, max_length=64)
    token = serializers.CharField(min_length=32, max_length=256, write_only=True)
    time_zone = serializers.CharField(max_length=100)

    def validate_time_zone(self, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise serializers.ValidationError("Unknown time zone") from None
        return value


class IdentityUpdate(SavedCommand):
    name = serializers.CharField(min_length=1, max_length=64)


class PushTokenUpdate(CommandSerializer):
    token = serializers.CharField(min_length=1, max_length=4096)


class EntitlementRequest(CommandSerializer):
    integrity_token = serializers.CharField(min_length=1, max_length=65536)


class GroupSettings(SavedCommand):
    name = serializers.CharField(min_length=1, max_length=80)
    alarm_permission = serializers.ChoiceField(choices=AlarmPermission.choices)
    notify_alarm_changes = serializers.BooleanField()
    notify_snoozed = serializers.BooleanField()
    notify_dismissed = serializers.BooleanField()
    notify_ignored = serializers.BooleanField()
    alarm_time_basis = serializers.ChoiceField(choices=AlarmTimeBasis.choices)
    alarm_time_zone = serializers.CharField(max_length=100)
    shared_answers = serializers.BooleanField()

    def validate_alarm_time_zone(self, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise serializers.ValidationError("Unknown time zone") from None
        return value


class GroupCreate(GroupSettings):
    id = serializers.UUIDField()
    membership_id = serializers.UUIDField()


class GroupUpdate(GroupSettings):
    membership_id = serializers.UUIDField()


class RoleUpdate(MemberCommand):
    role = serializers.ChoiceField(choices=MemberRole.choices)


class MembershipPreferences(MemberCommand):
    notify_membership = serializers.BooleanField()
    notify_administrative = serializers.BooleanField()


class InvitationCreate(CommandSerializer):
    id = serializers.UUIDField()
    membership_id = serializers.UUIDField()
    expires_in_hours = serializers.IntegerField(min_value=1, max_value=720, required=False)


class InvitationJoin(CommandSerializer):
    token = serializers.CharField(min_length=32, max_length=256)


class SoundSelection(CommandSerializer):
    mode = serializers.ChoiceField(choices=SoundMode.choices)
    sound_id = serializers.UUIDField(allow_null=True)
    title = serializers.CharField(max_length=200, required=False, allow_null=True)

    def validate(self, data):
        if (data["mode"] == SoundMode.SHARED) != (data["sound_id"] is not None):
            raise serializers.ValidationError("A sound ID is required only for shared audio")
        return data


class AlarmPayload(MemberCommand):
    local_time_ms = serializers.IntegerField(min_value=0, max_value=86399999)
    label = serializers.CharField(allow_null=True, allow_blank=True, max_length=200)
    enabled = serializers.BooleanField()
    days = serializers.ListField(child=serializers.IntegerField(min_value=0, max_value=6), max_length=7)
    vibrate = serializers.BooleanField()
    start_date = serializers.DateField()
    repeat_interval = serializers.IntegerField(min_value=1, max_value=999)
    repeat_unit = serializers.ChoiceField(choices=RepeatUnit.choices)
    repeat_anchor = serializers.ChoiceField(choices=RepeatAnchor.choices)
    repeat_duration = serializers.IntegerField(min_value=1, max_value=999, allow_null=True)
    repeat_duration_unit = serializers.ChoiceField(choices=RepeatUnit.choices)
    end_date = serializers.DateField(allow_null=True)
    end_occurrences = serializers.IntegerField(min_value=1, max_value=999, allow_null=True)
    advanced = serializers.BooleanField()
    snooze_enabled = serializers.BooleanField()
    snooze_minutes = serializers.IntegerField(min_value=1, max_value=1440)
    vibration_pattern = serializers.ListField(child=serializers.IntegerField(min_value=0), min_length=1, max_length=256)
    vibration_pattern_name = serializers.CharField(min_length=1, max_length=80)
    sound = SoundSelection()

    def validate(self, data):
        if len(set(data["days"])) != len(data["days"]):
            raise serializers.ValidationError({"days": "Weekdays must be unique"})
        if data["end_date"] is not None and data["end_date"] < data["start_date"]:
            raise serializers.ValidationError({"end_date": "End date precedes start date"})
        return data


class AlarmCreate(AlarmPayload):
    id = serializers.UUIDField()
    group_id = serializers.UUIDField()


class TimerPayload(MemberCommand):
    label = serializers.CharField(max_length=120, allow_null=True, allow_blank=True)
    duration_seconds = serializers.IntegerField(min_value=1, max_value=86400)
    increment_seconds = serializers.IntegerField(min_value=1, max_value=3600)
    expires_at = serializers.DateTimeField()
    vibrate = serializers.BooleanField()
    vibration_pattern = serializers.ListField(child=serializers.IntegerField(min_value=0), min_length=1, max_length=256)
    vibration_pattern_name = serializers.CharField(min_length=1, max_length=80)
    sound = SoundSelection()


class TimerCreate(TimerPayload):
    id = serializers.UUIDField()


class SoundUpload(CommandSerializer):
    id = serializers.UUIDField()
    membership_id = serializers.UUIDField()
    title = serializers.CharField(min_length=1, max_length=200)
    sha256 = serializers.RegexField(r"^[a-f0-9]{64}$")
    byte_length = serializers.IntegerField(min_value=1, max_value=33554432)
    duration_ms = serializers.IntegerField(min_value=1, max_value=300000)


class OccurrenceCommand(CommandSerializer):
    membership_id = serializers.UUIDField()
    alarm_revision = serializers.IntegerField(min_value=1)
    occurrence_key = serializers.CharField(min_length=1, max_length=200)
    trigger_at = serializers.DateTimeField()
    deadline_at = serializers.DateTimeField()
    cycle_date = serializers.DateField()

    def validate(self, data):
        if data["deadline_at"] <= data["trigger_at"]:
            raise serializers.ValidationError({"deadline_at": "Deadline must follow the trigger"})
        return data


class ActivityCommand(CommandSerializer):
    id = serializers.UUIDField()
    membership_id = serializers.UUIDField()
    alarm_revision = serializers.IntegerField(min_value=1)
    kind = serializers.ChoiceField(choices=ActivityKind.choices)
    occurred_at = serializers.DateTimeField()
    occurrence_key = serializers.CharField(max_length=200, allow_null=True)
    reason = serializers.CharField(max_length=40, allow_null=True)


class DeliveryCommand(CommandSerializer):
    revision = serializers.IntegerField(min_value=1)
