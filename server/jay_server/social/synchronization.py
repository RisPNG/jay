import json
from datetime import timedelta

from django.conf import settings
from django.core import signing
from django.core.serializers.json import DjangoJSONEncoder
from django.db import connection
from django.db.models import DateTimeField, Q, Subquery
from django.db.models.fields.json import KT
from django.db.models.functions import Cast
from django.utils import timezone

from .api.representations import ActivityRepresentation
from .errors import DomainError
from .models import DeliveryWork, GroupActivity, GroupMembership, SyncScope, SyncVersion


def publish_changes(scope_id, changes, group=None, actor=None, activity=None):
    scope = SyncScope.objects.select_for_update().get(pk=scope_id)
    scope.head_revision += 1
    scope.save(update_fields=["head_revision"])
    now = timezone.now()
    if activity is not None:
        history = GroupActivity.objects.create(
            group=group, revision=scope.head_revision, ordinal=len(changes),
            group_label=group.name, actor=actor, actor_label=actor.name if actor else None,
            **activity,
        )
        changes = [*changes, ("activity", str(history.id), history.action, ActivityRepresentation(history).data, history.recipient_id)]
    versions = []
    for ordinal, (kind, key, action, data, recipient_id) in enumerate(changes):
        SyncVersion.objects.filter(scope=scope, kind=kind, key=str(key), superseded_at=None).update(superseded_at=now)
        versions.append(SyncVersion(
            scope=scope, revision=scope.head_revision, ordinal=ordinal,
            kind=kind, key=str(key), action=action,
            representation=json.loads(json.dumps(data, cls=DjangoJSONEncoder)), recipient_id=recipient_id,
        ))
    SyncVersion.objects.bulk_create(versions)
    with connection.cursor() as cursor:
        cursor.execute("SELECT pg_notify('jay_changes', %s)", [str(scope.id)])
    payload = {"scope_id": str(scope.id), "revision": scope.head_revision}
    if group is not None:
        payload["group_id"] = str(group.id)
    else:
        payload["identity_id"] = scope.identity.pk
    DeliveryWork.objects.update_or_create(
        deduplication_key=f"push:{scope.id}",
        defaults={"kind": "push", "payload": payload, "failed_at": None, "error_code": None},
    )
    return scope.head_revision


def synchronize_scope(scope, identity, membership_id, cursor_token):
    if membership_id is not None:
        access = GroupMembership.objects.filter(pk=membership_id, identity=identity, identity__retired_at=None, removed_at=None, group__deleted_at=None, group__scope=scope).values("group__scope__head_revision", "group__scope__retention_floor").first()
        if access is None:
            raise DomainError("not_found", "Group not found", 404)
        scope.head_revision = access["group__scope__head_revision"]
        scope.retention_floor = access["group__scope__retention_floor"]
    else:
        scope = SyncScope.objects.filter(pk=scope.pk, identity=identity, identity__retired_at=None).first()
        if scope is None:
            raise DomainError("identity_retired", "This identity is retired", 401)
    binding = {"identity": identity.pk, "scope": str(scope.pk), "membership": str(membership_id) if membership_id else None}
    state = None
    if cursor_token:
        try:
            state = signing.loads(cursor_token, salt="jay.sync")
            if state.get("mode") != "checkpoint":
                signing.loads(cursor_token, salt="jay.sync", max_age=settings.SYNC_CURSOR_SECONDS)
        except signing.SignatureExpired:
            raise DomainError("cursor_expired", "Restart synchronization for this scope") from None
        except signing.BadSignature:
            raise DomainError("cursor_invalid", "Invalid synchronization cursor", 400) from None
        if any(state.get(key) != value for key, value in binding.items()):
            raise DomainError("cursor_invalid", "This cursor belongs to a different identity or membership", 400)
    if state is None or (state["mode"] != "snapshot" and state.get("after", 0) < scope.retention_floor):
        state = {**binding, "mode": "snapshot", "upper": scope.head_revision, "after": 0, "position": None, "recent_after": (timezone.now() - timedelta(days=2)).isoformat()}
    elif state["mode"] == "checkpoint":
        state = {**binding, "mode": "delta", "upper": scope.head_revision, "after": state["after"], "position": [state["after"], 2147483647]}
    upper = state["upper"]
    visible = SyncVersion.objects.filter(scope=scope, revision__lte=upper).filter(Q(recipient=None) | Q(recipient=identity))
    if state["mode"] == "snapshot":
        latest = visible.exclude(kind__in=["activity", "outcome"]).order_by("kind", "key", "-revision", "-ordinal").distinct("kind", "key").values("pk")
        rows = SyncVersion.objects.filter(pk__in=Subquery(latest)).exclude(action="delete").order_by("kind", "key")
        rows = rows.annotate(resolved_at=Cast(KT("representation__resolved_at"), DateTimeField())).exclude(Q(kind="occurrence") & (Q(representation__state="canceled") | (Q(resolved_at__isnull=False) & Q(resolved_at__lt=state["recent_after"]))))
        if state["position"]:
            kind, key = state["position"]
            rows = rows.filter(Q(kind__gt=kind) | Q(kind=kind, key__gt=key))
    else:
        revision, ordinal = state["position"]
        rows = visible.filter(Q(revision__gt=revision) | Q(revision=revision, ordinal__gt=ordinal)).order_by("revision", "ordinal")
    candidates = list(rows[:settings.SYNC_PAGE_SIZE + 1])
    items = []
    byte_count = 1024
    for row in candidates[:settings.SYNC_PAGE_SIZE]:
        item = {"kind": row.kind, "key": row.key, "action": row.action, "data": row.representation, "revision": row.revision, "ordinal": row.ordinal}
        size = len(json.dumps(item, separators=(",", ":"), ensure_ascii=False).encode())
        if byte_count + size > settings.SYNC_PAGE_BYTES:
            if not items:
                raise RuntimeError("Synchronization representation exceeds page envelope")
            break
        items.append(item)
        byte_count += size
    has_more = len(candidates) > len(items)
    if has_more:
        last = items[-1]
        state["position"] = [last["kind"], last["key"]] if state["mode"] == "snapshot" else [last["revision"], last["ordinal"]]
        next_state = state
    else:
        next_state = {**binding, "mode": "checkpoint", "after": upper}
    through = upper
    if has_more:
        through = items[-1]["revision"] if state["mode"] == "delta" else state["after"]
        if state["mode"] == "delta" and visible.filter(revision=through, ordinal__gt=items[-1]["ordinal"]).exists():
            through -= 1
    return {
        "scope_id": str(scope.id), "mode": state["mode"], "from_revision": state["after"],
        "through_revision": through, "next_cursor": signing.dumps(next_state, salt="jay.sync", compress=True),
        "has_more": has_more, "items": items, "server_time": timezone.now().isoformat(),
    }
