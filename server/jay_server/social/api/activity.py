from datetime import UTC, timedelta
from zoneinfo import ZoneInfo

from django.core import signing
from django.db.models import Q, TextField, Value
from django.db.models.fields.json import KT
from django.db.models.functions import Coalesce
from django.forms.models import model_to_dict
from django.shortcuts import get_object_or_404
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_alarm_protocol, require_membership
from ..alarms import record_alarm_response
from ..errors import DomainError
from ..models import AlarmOccurrence, Group, GroupActivity, SharedAlarm
from ..recurrence import next_alarm_trigger, occurrence_cycle_index
from ..synchronization import publish_changes
from ..transactions import mutation
from .representations import ActivityPageRepresentation, ActivityRepresentation, OccurrenceRepresentation, OutcomeRepresentation
from .serializers import ActivityCommand, OccurrenceCommand


class OccurrenceView(APIView):
    @extend_schema(request=OccurrenceCommand, responses=OccurrenceRepresentation)
    def put(self, request, alarm_id):
        serializer = OccurrenceCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id, deleted_at=None)
        with mutation(request, data, [alarm.group.scope_id]) as receipt:
            alarm = get_object_or_404(SharedAlarm.objects.select_for_update().select_related("group"), pk=alarm_id, deleted_at=None)
            require_membership(alarm.group, request.user, data["membership_id"])
            require_alarm_protocol(alarm.repeat_unit, request.supports_hourly_alarms)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if alarm.revision != data["alarm_revision"] or not alarm.enabled:
                raise DomainError("revision_changed", "The alarm schedule changed")
            zone = alarm.group.alarm_time_zone if alarm.group.alarm_time_basis == "group_time_zone" else request.user.time_zone
            if data["trigger_at"].astimezone(ZoneInfo(zone)).date() != data["cycle_date"]:
                raise DomainError("cycle_invalid", "The cycle date does not match the trigger", 400)
            payload = model_to_dict(alarm)
            if alarm.repeat_unit == "HOUR" and next_alarm_trigger(payload, zone, data["trigger_at"] - timedelta(microseconds=1)) != data["trigger_at"].astimezone(UTC):
                raise DomainError("cycle_invalid", "The trigger does not match the hourly schedule", 400)
            data["cycle_index"] = occurrence_cycle_index(payload, zone, data["trigger_at"])
            data["deadline_at"] = data["trigger_at"] + timedelta(minutes=alarm.timeout_minutes)
            data.pop("membership_id")
            occurrence, created = AlarmOccurrence.objects.get_or_create(alarm=alarm, identity=request.user, occurrence_key=data["occurrence_key"], defaults={"group": alarm.group, **{key: value for key, value in data.items() if key != "occurrence_key"}})
            if not created and occurrence.state in {"pending", "canceled"} and (occurrence.snoozed_until is None or occurrence.alarm_revision != data["alarm_revision"]):
                if occurrence.alarm_revision != data["alarm_revision"] or occurrence.state == "canceled":
                    occurrence.snooze_count = 0
                for name, value in data.items():
                    setattr(occurrence, name, value)
                occurrence.state, occurrence.resolved_at, occurrence.snoozed_until, occurrence.snoozed_at = "pending", None, None, None
                occurrence.save()
            publish_changes(alarm.group.scope_id, [("occurrence", str(occurrence.pk), "upsert", OccurrenceRepresentation(occurrence).data, request.user.pk)], group=alarm.group)
            receipt.status, receipt.response = 200, OccurrenceRepresentation(occurrence).data
        return Response(receipt.response)


class AlarmActivityView(APIView):
    @extend_schema(request=ActivityCommand, responses={201: OutcomeRepresentation})
    def post(self, request, alarm_id):
        serializer = ActivityCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id, deleted_at=None)
        with mutation(request, data, [alarm.group.scope_id]) as receipt:
            alarm = get_object_or_404(SharedAlarm.objects.select_for_update().select_related("group"), pk=alarm_id, deleted_at=None)
            require_membership(alarm.group, request.user, data["membership_id"])
            require_alarm_protocol(alarm.repeat_unit, request.supports_hourly_alarms)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            activity = record_alarm_response(alarm, request.user, data)
            receipt.status, receipt.response = 201, OutcomeRepresentation(activity).data
        return Response(receipt.response, status=201)

    @extend_schema(responses=ActivityPageRepresentation)
    def get(self, request, alarm_id):
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id)
        require_membership(alarm.group, request.user)
        require_alarm_protocol(alarm.repeat_unit, request.supports_hourly_alarms)
        return Response(activity_page(request, alarm.group, str(alarm.id)))


class GroupActivityView(APIView):
    @extend_schema(responses=ActivityPageRepresentation)
    def get(self, request, group_id):
        group = get_object_or_404(Group, pk=group_id)
        require_membership(group, request.user)
        return Response(activity_page(request, group))


def activity_page(request, group, alarm_id=None):
    try:
        limit = int(request.query_params.get("limit", "50"))
    except ValueError:
        raise DomainError("limit_invalid", "Limit must be an integer", 400) from None
    if not 1 <= limit <= 100:
        raise DomainError("limit_invalid", "Limit must be between 1 and 100", 400)
    rows = GroupActivity.objects.filter(group=group).filter(Q(recipient=None) | Q(recipient=request.user))
    if not request.supports_hourly_alarms:
        hourly_alarm_ids = [str(key) for key in SharedAlarm.objects.filter(group=group, repeat_unit="HOUR").values_list("pk", flat=True)]
        rows = rows.alias(
            schedule_unit=Coalesce(KT("details__repeat_unit"), Value(""), output_field=TextField()),
            previous_schedule_unit=Coalesce(KT("details__previous_repeat_unit"), Value(""), output_field=TextField()),
        ).exclude(
            Q(entity_type__in=["alarm", "outcome", "delivery"], entity_id__in=hourly_alarm_ids)
            | Q(schedule_unit="HOUR")
            | Q(previous_schedule_unit="HOUR")
        )
    if alarm_id:
        rows = rows.filter(entity_id=alarm_id)
    before = request.query_params.get("before")
    if before:
        try:
            state = signing.loads(before, salt="jay.history")
            if state["group"] != str(group.pk) or state["identity"] != request.user.pk or state["alarm"] != alarm_id:
                raise signing.BadSignature()
            rows = rows.filter(Q(revision__lt=state["revision"]) | Q(revision=state["revision"], ordinal__lt=state["ordinal"]))
        except (signing.BadSignature, KeyError, TypeError):
            raise DomainError("cursor_invalid", "Invalid history cursor", 400) from None
    records = list(rows.order_by("-revision", "-ordinal")[:limit + 1])
    next_before = None
    if len(records) > limit:
        last = records[limit - 1]
        next_before = signing.dumps({"group": str(group.pk), "identity": request.user.pk, "alarm": alarm_id, "revision": last.revision, "ordinal": last.ordinal}, salt="jay.history", compress=True)
    return {"items": ActivityRepresentation(records[:limit], many=True).data, "next_before": next_before}
