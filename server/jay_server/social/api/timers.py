from uuid import uuid4

from django.shortcuts import get_object_or_404
from django.utils import timezone
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_membership, select_shared_sound
from ..errors import DomainError
from ..models import Group, SharedTimer
from ..synchronization import publish_changes
from ..timers import record_timer_response
from ..transactions import accept_saved_state, mutation
from .representations import SoundRepresentation, TimerOccurrenceRepresentation, TimerRepresentation
from .serializers import TimerCancelCommand, TimerCreate, TimerOccurrenceCommand, TimerPayload


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
            data.setdefault("timeout_minutes", 10)
            sound = select_shared_sound(group, request.user, selection, installation=request.auth)
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
        data = serializer.to_internal_value(request.data)
        timer = get_object_or_404(SharedTimer.objects.select_related("group"), pk=timer_id, deleted_at=None)
        with mutation(request, data, [timer.group.scope_id]) as receipt:
            timer = get_object_or_404(SharedTimer.objects.select_for_update().select_related("group"), pk=timer_id, deleted_at=None)
            require_membership(timer.group, request.user, data["membership_id"], editor=True)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            serializer.instance = timer
            data = serializer.validate(data)
            accept_saved_state(timer, data.pop("saved_at"), receipt.operation_id, TimerRepresentation(timer).data)
            data.pop("membership_id")
            run_id = data.pop("run_id", None)
            schedule_changed = any(name in data and getattr(timer, name) != data[name] for name in ["expires_at", "duration_seconds", "repeat_enabled", "repeat_count", "repeat_index"])
            if run_id is not None:
                if schedule_changed and run_id == timer.run_id:
                    raise DomainError("run_changed", "A changed timer schedule requires a new run ID")
                timer.run_id = run_id
            elif schedule_changed:
                timer.run_id = uuid4()
            selection = data.pop("sound")
            timer.sound = select_shared_sound(timer.group, request.user, selection, installation=request.auth)
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

    @extend_schema(request=TimerCancelCommand, responses={204: None})
    def delete(self, request, timer_id):
        serializer = TimerCancelCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        timer = get_object_or_404(SharedTimer.objects.select_related("group"), pk=timer_id)
        with mutation(request, data, [timer.group.scope_id]) as receipt:
            timer = get_object_or_404(SharedTimer.objects.select_for_update().select_related("group"), pk=timer_id)
            member = require_membership(timer.group, request.user, data["membership_id"], editor=True)
            if receipt.completed_at:
                return Response(status=receipt.status)
            if timer.deleted_at is None:
                run_id = data.get("run_id")
                if run_id is not None and run_id != timer.run_id:
                    raise DomainError("run_changed", "This cancellation belongs to an old timer run")
                now = timezone.now()
                if run_id is None and timer.repeat_enabled:
                    if now < timer.expires_at:
                        raise DomainError("client_upgrade_required", "Update to Jay 1.2.5 to cancel a repeating timer", 400)
                    record_timer_response(timer, member, timer.repeat_index, now)
                else:
                    timer.deleted_at = now
                    timer.save(update_fields=["deleted_at"])
                    publish_changes(timer.group.scope_id, [("timer", str(timer.pk), "delete", {"id": str(timer.pk)}, None)], group=timer.group, actor=request.user)
            receipt.status, receipt.response = 204, None
        return Response(status=204)


class TimerOccurrenceView(APIView):
    @extend_schema(request=TimerOccurrenceCommand, responses=TimerOccurrenceRepresentation)
    def post(self, request, timer_id):
        serializer = TimerOccurrenceCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        timer = get_object_or_404(SharedTimer.objects.select_related("group"), pk=timer_id)
        with mutation(request, data, [timer.group.scope_id]) as receipt:
            timer = get_object_or_404(SharedTimer.objects.select_for_update().select_related("group"), pk=timer_id)
            member = require_membership(timer.group, request.user, data["membership_id"])
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if data["run_id"] != timer.run_id:
                raise DomainError("run_changed", "This response belongs to an old timer run")
            answered = record_timer_response(timer, member, data["repeat_index"], data["occurred_at"])
            receipt.status, receipt.response = 200, TimerOccurrenceRepresentation(answered).data
        return Response(receipt.response)
