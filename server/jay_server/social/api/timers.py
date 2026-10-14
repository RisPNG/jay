from datetime import timedelta
from uuid import uuid4

from django.shortcuts import get_object_or_404
from django.utils import timezone
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_membership, select_shared_sound
from ..errors import DomainError
from ..models import Group, GroupMembership, SharedTimer, TimerOccurrence
from ..synchronization import publish_changes
from ..transactions import accept_saved_state, mutation
from .representations import SoundRepresentation, TimerOccurrenceRepresentation, TimerRepresentation
from .serializers import MembershipCommand, TimerCreate, TimerOccurrenceCommand, TimerPayload


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


class TimerOccurrenceView(APIView):
    @extend_schema(request=TimerOccurrenceCommand, responses=TimerOccurrenceRepresentation)
    def post(self, request, timer_id):
        serializer = TimerOccurrenceCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        timer = get_object_or_404(SharedTimer.objects.select_related("group"), pk=timer_id, deleted_at=None)
        with mutation(request, data, [timer.group.scope_id]) as receipt:
            timer = get_object_or_404(SharedTimer.objects.select_for_update().select_related("group"), pk=timer_id, deleted_at=None)
            member = require_membership(timer.group, request.user, data["membership_id"])
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if data["run_id"] != timer.run_id:
                raise DomainError("run_changed", "This response belongs to an old timer run")
            index = data["repeat_index"]
            if index < timer.repeat_index or (not timer.repeat_enabled and index != 0) or (timer.repeat_enabled and timer.repeat_count and index > timer.repeat_count):
                raise DomainError("occurrence_invalid", "This repeat does not belong to the timer run", 400)
            now = timezone.now()
            elapsed_index = timer.repeat_index + (now - timer.expires_at) // timedelta(seconds=timer.duration_seconds)
            if index > elapsed_index:
                raise DomainError("occurrence_invalid", "This timer occurrence has not expired", 400)
            trigger = timer.expires_at + timedelta(seconds=(index - timer.repeat_index) * timer.duration_seconds)
            if data["occurred_at"] < trigger:
                raise DomainError("occurrence_invalid", "The response precedes this timer occurrence", 400)
            if data["occurred_at"] > now + timedelta(minutes=2):
                raise DomainError("clock_invalid", "The response time is ahead of the server clock")
            existing = TimerOccurrence.objects.filter(timer=timer, membership=member, run_id=timer.run_id, repeat_index=index).first()
            if existing is not None:
                receipt.status, receipt.response = 200, TimerOccurrenceRepresentation(existing).data
                return Response(receipt.response)
            members = GroupMembership.objects.filter(group=timer.group, removed_at=None, identity__retired_at=None).order_by("identity_id")
            if not timer.group.shared_answers:
                members = members.filter(pk=member.pk)
            changes = []
            for recipient in members:
                occurrence, created = TimerOccurrence.objects.get_or_create(
                    timer=timer, membership=recipient, run_id=timer.run_id, repeat_index=index,
                    defaults={"group": timer.group, "identity_id": recipient.identity_id, "trigger_at": trigger, "deadline_at": trigger + timedelta(minutes=timer.timeout_minutes), "resolved_at": now},
                )
                if created:
                    changes.append(("timer_occurrence", str(occurrence.pk), "upsert", TimerOccurrenceRepresentation(occurrence).data, recipient.identity_id))
                if recipient.pk == member.pk:
                    answered = occurrence
            if changes:
                publish_changes(timer.group.scope_id, changes, group=timer.group, actor=request.user)
            receipt.status, receipt.response = 200, TimerOccurrenceRepresentation(answered).data
        return Response(receipt.response)
