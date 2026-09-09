from django.shortcuts import get_object_or_404
from django.utils import timezone
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_membership, select_shared_sound
from ..models import Group, SharedTimer
from ..synchronization import publish_changes
from ..transactions import accept_saved_state, mutation
from .representations import SoundRepresentation, TimerRepresentation
from .serializers import MembershipCommand, TimerCreate, TimerPayload


class TimerListView(APIView):
    @extend_schema(request=TimerCreate, responses={201: TimerRepresentation})
    def post(self, request, group_id):
        serializer = TimerCreate(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        group = get_object_or_404(Group, pk=group_id)
        with mutation(request, data, [group.scope_id]) as receipt:
            group = Group.objects.select_for_update().get(pk=group_id)
            require_membership(group, request.user, data["membership_id"], editor=True)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            data.pop("membership_id")
            selection = data.pop("sound")
            sound = select_shared_sound(group, request.user, selection)
            timer = SharedTimer.objects.create(**data, group=group, sound=sound, sound_mode=selection["mode"], started_by=request.user, save_id=receipt.operation_id)
            changes = [("timer", str(timer.pk), "upsert", TimerRepresentation(timer).data, None)]
            if sound:
                changes.append(("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None))
            publish_changes(group.scope_id, changes, group=group, actor=request.user)
            receipt.status, receipt.response = 201, TimerRepresentation(timer).data
        return Response(receipt.response, status=201)


class TimerView(APIView):
    @extend_schema(request=TimerPayload, responses=TimerRepresentation)
    def put(self, request, timer_id):
        serializer = TimerPayload(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        timer = get_object_or_404(SharedTimer.objects.select_related("group"), pk=timer_id, deleted_at=None)
        with mutation(request, data, [timer.group.scope_id]) as receipt:
            timer = get_object_or_404(SharedTimer.objects.select_for_update().select_related("group"), pk=timer_id, deleted_at=None)
            require_membership(timer.group, request.user, data["membership_id"], editor=True)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            accept_saved_state(timer, data.pop("saved_at"), receipt.operation_id, TimerRepresentation(timer).data)
            data.pop("membership_id")
            selection = data.pop("sound")
            timer.sound = select_shared_sound(timer.group, request.user, selection, timer.sound_id)
            timer.sound_mode = selection["mode"]
            for name, value in data.items():
                setattr(timer, name, value)
            timer.save()
            changes = [("timer", str(timer.pk), "upsert", TimerRepresentation(timer).data, None)]
            if timer.sound:
                changes.append(("sound", str(timer.sound_id), "upsert", SoundRepresentation(timer.sound).data, None))
            publish_changes(timer.group.scope_id, changes, group=timer.group, actor=request.user)
            receipt.status, receipt.response = 200, TimerRepresentation(timer).data
        return Response(receipt.response)

    @extend_schema(request=MembershipCommand, responses={204: None})
    def delete(self, request, timer_id):
        serializer = MembershipCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        timer = get_object_or_404(SharedTimer.objects.select_related("group"), pk=timer_id)
        with mutation(request, data, [timer.group.scope_id]) as receipt:
            if receipt.completed_at:
                return Response(status=receipt.status)
            timer = get_object_or_404(SharedTimer.objects.select_for_update().select_related("group"), pk=timer_id, deleted_at=None)
            require_membership(timer.group, request.user, data["membership_id"], editor=True)
            timer.deleted_at = timezone.now()
            timer.save(update_fields=["deleted_at"])
            publish_changes(timer.group.scope_id, [("timer", str(timer.pk), "delete", {"id": str(timer.pk)}, None)], group=timer.group, actor=request.user)
            receipt.status, receipt.response = 204, None
        return Response(status=204)
