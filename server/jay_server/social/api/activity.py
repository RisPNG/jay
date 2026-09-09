from zoneinfo import ZoneInfo

from django.core import signing
from django.db.models import Q
from django.shortcuts import get_object_or_404
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_membership
from ..alarms import record_alarm_response
from ..errors import DomainError
from ..models import AlarmOccurrence, Group, GroupActivity, SharedAlarm
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
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if alarm.revision != data["alarm_revision"] or not alarm.enabled:
                raise DomainError("revision_changed", "The alarm schedule changed")
            zone = alarm.group.alarm_time_zone if alarm.group.alarm_time_basis == "group_time_zone" else request.user.time_zone
            if data["trigger_at"].astimezone(ZoneInfo(zone)).date() != data["cycle_date"]:
                raise DomainError("cycle_invalid", "The cycle date does not match the trigger", 400)
            data.pop("membership_id")
            occurrence, created = AlarmOccurrence.objects.get_or_create(alarm=alarm, identity=request.user, occurrence_key=data["occurrence_key"], defaults={"group": alarm.group, **{key: value for key, value in data.items() if key != "occurrence_key"}})
            if not created and occurrence.state in {"pending", "canceled"}:
                for name, value in data.items():
                    setattr(occurrence, name, value)
                occurrence.state, occurrence.resolved_at = "pending", None
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
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            activity = record_alarm_response(alarm, request.user, data)
            receipt.status, receipt.response = 201, OutcomeRepresentation(activity).data
        return Response(receipt.response, status=201)

    @extend_schema(responses=ActivityPageRepresentation)
    def get(self, request, alarm_id):
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id)
        require_membership(alarm.group, request.user)
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
