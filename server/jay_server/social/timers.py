from datetime import timedelta

from django.utils import timezone

from .api.representations import TimerOccurrenceRepresentation
from .errors import DomainError
from .models import GroupMembership, TimerOccurrence
from .synchronization import publish_changes


def record_timer_response(timer, member, index, occurred_at):
    existing = TimerOccurrence.objects.filter(timer=timer, membership=member, run_id=timer.run_id, repeat_index=index).first()
    final = not timer.repeat_enabled or bool(timer.repeat_count and index == timer.repeat_count)
    if timer.deleted_at is not None and not (timer.group.shared_answers and final and existing is not None):
        raise DomainError("not_found", "Timer not found", 404)
    if index < timer.repeat_index or (not timer.repeat_enabled and index != 0) or (timer.repeat_enabled and timer.repeat_count and index > timer.repeat_count):
        raise DomainError("occurrence_invalid", "This repeat does not belong to the timer run", 400)
    now = timezone.now()
    elapsed_index = timer.repeat_index + (now - timer.expires_at) // timedelta(seconds=timer.duration_seconds)
    if index > elapsed_index:
        raise DomainError("occurrence_invalid", "This timer occurrence has not expired", 400)
    trigger = timer.expires_at + timedelta(seconds=(index - timer.repeat_index) * timer.duration_seconds)
    if occurred_at < trigger:
        raise DomainError("occurrence_invalid", "The response precedes this timer occurrence", 400)
    if occurred_at > now + timedelta(minutes=2):
        raise DomainError("clock_invalid", "The response time is ahead of the server clock")
    if existing is not None:
        return existing
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
    if timer.group.shared_answers and final:
        timer.deleted_at = now
        timer.save(update_fields=["deleted_at"])
        changes.append(("timer", str(timer.pk), "delete", {"id": str(timer.pk)}, None))
    if changes:
        publish_changes(timer.group.scope_id, changes, group=timer.group, actor=member.identity)
    return answered
