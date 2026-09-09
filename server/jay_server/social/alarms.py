from datetime import timedelta
import hashlib
import json
from uuid import uuid4
from zoneinfo import ZoneInfo

from django.forms.models import model_to_dict
from django.core.serializers.json import DjangoJSONEncoder
from django.utils import timezone

from .api.representations import AlarmRepresentation, OccurrenceRepresentation, OutcomeRepresentation
from .errors import DomainError
from .models import AlarmActivity, AlarmOccurrence, GroupMembership
from .recurrence import next_alarm_trigger
from .synchronization import publish_changes


def schedule_occurrences(alarm, identity_ids, after=None):
    now = timezone.now()
    changes = []
    outdated = AlarmOccurrence.objects.filter(alarm=alarm, identity_id__in=identity_ids, state="pending")
    if alarm.enabled and alarm.deleted_at is None:
        outdated = outdated.exclude(alarm_revision=alarm.revision)
    for occurrence in outdated:
        occurrence.state, occurrence.resolved_at = "canceled", now
        occurrence.save(update_fields=["state", "resolved_at"])
        changes.append(("occurrence", str(occurrence.pk), "upsert", OccurrenceRepresentation(occurrence).data, occurrence.identity_id))
    if not alarm.enabled or alarm.deleted_at or alarm.group.deleted_at:
        return changes
    payload = model_to_dict(alarm)
    members = GroupMembership.objects.select_related("identity").filter(group=alarm.group, removed_at=None, identity_id__in=identity_ids, identity__retired_at=None)
    for member in members:
        if alarm.end_occurrences == 1 and AlarmOccurrence.objects.filter(alarm=alarm, identity=member.identity, alarm_revision=alarm.revision, state__in=["dismissed", "ignored"]).exists():
            continue
        zone = alarm.group.alarm_time_zone if alarm.group.alarm_time_basis == "group_time_zone" else member.identity.time_zone
        trigger = next_alarm_trigger(payload, zone, after or now)
        if trigger is None:
            continue
        key = str(int(trigger.timestamp() * 1000))
        occurrence, created = AlarmOccurrence.objects.get_or_create(
            alarm=alarm, identity=member.identity, occurrence_key=key,
            defaults={"group": alarm.group, "alarm_revision": alarm.revision, "cycle_date": trigger.astimezone(ZoneInfo(zone)).date(), "trigger_at": trigger, "deadline_at": trigger + timedelta(minutes=10)},
        )
        if not created:
            if occurrence.state not in {"pending", "canceled"}:
                continue
            if occurrence.alarm_revision == alarm.revision and occurrence.state == "pending":
                continue
            occurrence.alarm_revision = alarm.revision
            occurrence.trigger_at = trigger
            occurrence.deadline_at = trigger + timedelta(minutes=10)
            occurrence.cycle_date = trigger.astimezone(ZoneInfo(zone)).date()
            occurrence.state, occurrence.resolved_at = "pending", None
            occurrence.save()
        changes.append(("occurrence", str(occurrence.pk), "upsert", OccurrenceRepresentation(occurrence).data, member.identity_id))
    return changes


def evaluate_alarm_cycle(alarm, cycle_date):
    if alarm.deleted_at or not alarm.enabled or (alarm.last_evaluated_cycle_date and alarm.last_evaluated_cycle_date >= cycle_date):
        return []
    expected = GroupMembership.objects.filter(group=alarm.group, removed_at=None, identity__retired_at=None).count()
    occurrences = AlarmOccurrence.objects.filter(alarm=alarm, alarm_revision=alarm.revision, cycle_date=cycle_date).exclude(state="canceled")
    if occurrences.count() < expected or occurrences.filter(state="pending").exists():
        return []
    active = occurrences.filter(state__in=["snoozed", "dismissed"]).exists() or AlarmActivity.objects.filter(alarm=alarm, alarm_revision=alarm.revision, kind__in=["snoozed", "dismissed"], occurrence_key__in=occurrences.values("occurrence_key")).exists()
    alarm.last_evaluated_cycle_date = cycle_date
    alarm.inactive_cycle_streak = 0 if active else min(3, alarm.inactive_cycle_streak + 1)
    if alarm.inactive_cycle_streak == 3:
        alarm.deleted_at = timezone.now()
        alarm.revision += 1
        AlarmOccurrence.objects.filter(alarm=alarm, state="pending").update(state="canceled", resolved_at=timezone.now())
    alarm.save()
    return [("alarm", str(alarm.pk), "delete" if alarm.deleted_at else "upsert", {"id": str(alarm.pk)} if alarm.deleted_at else AlarmRepresentation(alarm).data, None)]


def record_alarm_response(alarm, identity, data):
    if alarm.deleted_at or alarm.group.deleted_at:
        raise DomainError("not_found", "Alarm not found", 404)
    if data["alarm_revision"] != alarm.revision:
        raise DomainError("revision_changed", "This response belongs to an old alarm schedule")
    fingerprint = hashlib.sha256(json.dumps(
        {key: data.get(key) for key in ["alarm_revision", "kind", "occurred_at", "occurrence_key", "reason"]},
        cls=DjangoJSONEncoder, sort_keys=True, separators=(",", ":"),
    ).encode()).hexdigest()
    existing = AlarmActivity.objects.filter(pk=data["id"]).first()
    if existing:
        if existing.alarm_id != alarm.pk or existing.identity_id != identity.pk or existing.request_fingerprint != fingerprint:
            raise DomainError("activity_mismatch", "This activity ID belongs to a different response")
        return existing
    occurrence_key = data.get("occurrence_key")
    occurrences = AlarmOccurrence.objects.filter(alarm=alarm, identity=identity, alarm_revision=alarm.revision)
    occurrence = occurrences.filter(occurrence_key=occurrence_key).first() if occurrence_key else occurrences.filter(trigger_at__lte=data["occurred_at"], state__in=["pending", "ignored"]).order_by("-trigger_at").first()
    if occurrence:
        occurrence_key = occurrence.occurrence_key
    previous = AlarmActivity.objects.filter(alarm=alarm, identity=identity, occurrence_key=occurrence_key, kind__in=["dismissed", "ignored"]).exclude(reason="corrected").order_by("-created_at").first() if occurrence_key else None
    changes = []
    if previous:
        if previous.kind == "dismissed" or data["kind"] == "ignored" or data["occurred_at"] >= previous.occurred_at:
            return previous
        previous.reason = "corrected"
        previous.save(update_fields=["reason"])
        changes.append(("outcome", str(previous.pk), "upsert", OutcomeRepresentation(previous).data, None))
    activity = AlarmActivity.objects.create(
        id=data["id"], alarm=alarm, group=alarm.group, identity=identity, alarm_revision=alarm.revision,
        kind=data["kind"], occurred_at=data["occurred_at"], occurrence_key=occurrence_key, reason=data.get("reason"), request_fingerprint=fingerprint,
    )
    changes.append(("outcome", str(activity.pk), "upsert", OutcomeRepresentation(activity).data, None))
    if occurrence:
        if activity.kind == "snoozed":
            occurrence.state, occurrence.resolved_at = "pending", None
            occurrence.deadline_at = activity.occurred_at + timedelta(minutes=alarm.snooze_minutes + 10)
        else:
            occurrence.state, occurrence.resolved_at = activity.kind, timezone.now()
        occurrence.save()
        changes.append(("occurrence", str(occurrence.pk), "upsert", OccurrenceRepresentation(occurrence).data, identity.pk))
        changes.extend(schedule_occurrences(alarm, [identity.pk], occurrence.trigger_at + timedelta(milliseconds=1)))
        if alarm.group.shared_answers:
            shared = AlarmOccurrence.objects.filter(alarm=alarm, alarm_revision=alarm.revision, cycle_date=occurrence.cycle_date, state="pending").exclude(identity=identity)
            for other in shared:
                other.state, other.resolved_at = activity.kind, timezone.now()
                other.save(update_fields=["state", "resolved_at"])
                changes.append(("occurrence", str(other.pk), "upsert", OccurrenceRepresentation(other).data, other.identity_id))
                changes.extend(schedule_occurrences(alarm, [other.identity_id], other.trigger_at + timedelta(milliseconds=1)))
        changes.extend(evaluate_alarm_cycle(alarm, occurrence.cycle_date))
    publish_changes(alarm.group.scope_id, changes, group=alarm.group, actor=identity, activity={
        "entity_type": "outcome", "entity_id": str(alarm.pk), "action": activity.kind,
        "entity_label": alarm.label, "entity_time": alarm.local_time_ms,
        "subject": identity, "subject_label": identity.name,
        "details": {"activity_id": str(activity.pk), "alarm_revision": alarm.revision, "occurrence_key": occurrence_key, "reason": activity.reason},
    })
    return activity
