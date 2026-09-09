from django.shortcuts import get_object_or_404
from django.utils import timezone
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_membership, select_shared_sound
from ..models import AlarmDelivery, AlarmOccurrence, DeliveryWork, Group, SharedAlarm
from ..synchronization import publish_changes
from ..transactions import accept_saved_state, mutation
from .representations import AlarmRepresentation, SoundRepresentation
from .serializers import MembershipCommand, AlarmCreate, AlarmPayload, DeliveryCommand


class AlarmListView(APIView):
    @extend_schema(request=AlarmCreate, responses={201: AlarmRepresentation})
    def post(self, request):
        serializer = AlarmCreate(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        group = get_object_or_404(Group, pk=data["group_id"])
        with mutation(request, data, [group.scope_id]) as receipt:
            group = Group.objects.select_for_update().get(pk=group.pk)
            require_membership(group, request.user, data["membership_id"], editor=True)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            data.pop("membership_id")
            selection = data.pop("sound")
            sound = select_shared_sound(group, request.user, selection)
            alarm = SharedAlarm.objects.create(**data, sound_mode=selection["mode"], sound=sound, save_id=receipt.operation_id, created_by=request.user, updated_by=request.user)
            changes = [("alarm", str(alarm.pk), "upsert", AlarmRepresentation(alarm).data, None)]
            if sound:
                changes.append(("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None))
            publish_changes(group.scope_id, changes, group=group, actor=request.user, activity={"entity_type": "alarm", "entity_id": str(alarm.pk), "action": "created", "entity_label": alarm.label, "entity_time": alarm.local_time_ms})
            DeliveryWork.objects.create(kind="reschedule", deduplication_key=f"reschedule:alarm:{alarm.pk}:{alarm.revision}", payload={"alarm_id": str(alarm.pk)})
            receipt.status, receipt.response = 201, AlarmRepresentation(alarm).data
        return Response(receipt.response, status=201)


class AlarmView(APIView):
    @extend_schema(request=AlarmPayload, responses=AlarmRepresentation)
    def put(self, request, alarm_id):
        serializer = AlarmPayload(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id, deleted_at=None)
        with mutation(request, data, [alarm.group.scope_id]) as receipt:
            alarm = get_object_or_404(SharedAlarm.objects.select_for_update().select_related("group"), pk=alarm_id, deleted_at=None)
            require_membership(alarm.group, request.user, data["membership_id"], editor=True)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            accept_saved_state(alarm, data.pop("saved_at"), receipt.operation_id, AlarmRepresentation(alarm).data)
            data.pop("membership_id")
            selection = data.pop("sound")
            alarm.sound = select_shared_sound(alarm.group, request.user, selection, alarm.sound_id)
            alarm.sound_mode = selection["mode"]
            for name, value in data.items():
                setattr(alarm, name, value)
            alarm.updated_by = request.user
            alarm.revision += 1
            alarm.inactive_cycle_streak = 0
            alarm.last_evaluated_cycle_date = None
            alarm.save()
            AlarmOccurrence.objects.filter(alarm=alarm, state="pending").update(state="canceled", resolved_at=timezone.now())
            changes = [("alarm", str(alarm.pk), "upsert", AlarmRepresentation(alarm).data, None)]
            if alarm.sound:
                changes.append(("sound", str(alarm.sound_id), "upsert", SoundRepresentation(alarm.sound).data, None))
            publish_changes(alarm.group.scope_id, changes, group=alarm.group, actor=request.user, activity={"entity_type": "alarm", "entity_id": str(alarm.pk), "action": "updated", "entity_label": alarm.label, "entity_time": alarm.local_time_ms})
            DeliveryWork.objects.create(kind="reschedule", deduplication_key=f"reschedule:alarm:{alarm.pk}:{alarm.revision}", payload={"alarm_id": str(alarm.pk)})
            receipt.status, receipt.response = 200, AlarmRepresentation(alarm).data
        return Response(receipt.response)

    @extend_schema(request=MembershipCommand, responses={204: None})
    def delete(self, request, alarm_id):
        serializer = MembershipCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id)
        with mutation(request, data, [alarm.group.scope_id]) as receipt:
            if receipt.completed_at:
                return Response(status=receipt.status)
            alarm = get_object_or_404(SharedAlarm.objects.select_for_update().select_related("group"), pk=alarm_id, deleted_at=None)
            require_membership(alarm.group, request.user, data["membership_id"], editor=True)
            alarm.deleted_at, alarm.revision = timezone.now(), alarm.revision + 1
            alarm.save(update_fields=["deleted_at", "revision"])
            AlarmOccurrence.objects.filter(alarm=alarm, state="pending").update(state="canceled", resolved_at=timezone.now())
            publish_changes(alarm.group.scope_id, [("alarm", str(alarm.pk), "delete", {"id": str(alarm.pk)}, None)], group=alarm.group, actor=request.user, activity={"entity_type": "alarm", "entity_id": str(alarm.pk), "action": "deleted", "entity_label": alarm.label, "entity_time": alarm.local_time_ms})
            receipt.status, receipt.response = 204, None
        return Response(status=204)


class AlarmDeliveryView(APIView):
    @extend_schema(request=DeliveryCommand, responses={204: None})
    def post(self, request, alarm_id):
        serializer = DeliveryCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        alarm = get_object_or_404(SharedAlarm.objects.select_related("group"), pk=alarm_id, deleted_at=None)
        with mutation(request, data, [alarm.group.scope_id]) as receipt:
            require_membership(alarm.group, request.user)
            if not receipt.completed_at:
                from ..errors import DomainError
                if data["revision"] > SharedAlarm.objects.get(pk=alarm_id).revision:
                    raise DomainError("revision_invalid", "This alarm revision does not exist")
                _, created = AlarmDelivery.objects.get_or_create(alarm=alarm, identity=request.user, revision=data["revision"])
                if created:
                    publish_changes(alarm.group.scope_id, [], group=alarm.group, actor=request.user, activity={"entity_type": "delivery", "entity_id": str(alarm.pk), "action": "received", "entity_label": alarm.label, "entity_time": alarm.local_time_ms, "details": {"alarm_revision": data["revision"]}})
                receipt.status, receipt.response = 204, None
        return Response(status=204)
