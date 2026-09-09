import hashlib
import json
from contextlib import contextmanager
from datetime import timedelta
from uuid import UUID

from django.core.serializers.json import DjangoJSONEncoder
from django.db import connection, transaction
from django.utils import timezone

from .errors import DomainError
from .models import GroupMembership, Identity, OperationReceipt, SyncScope


@contextmanager
def mutation(request, payload, scope_ids=(), identity_ids=(), exclusive_identity=False, include_membership_scopes=False, reserve=False):
    try:
        operation_id = UUID(request.headers.get("Idempotency-Key", ""))
    except ValueError:
        raise DomainError("idempotency_required", "Idempotency-Key must be a UUID", 400) from None
    fingerprint = hashlib.sha256(json.dumps(
        [request.user.pk, bytes(request.user.token_hash).hex(), request.method, request.path, sorted(request.query_params.lists()), payload],
        cls=DjangoJSONEncoder, sort_keys=True, separators=(",", ":"),
    ).encode()).hexdigest()
    with transaction.atomic():
        receipt, _ = OperationReceipt.objects.get_or_create(
            identity=request.user, operation_id=operation_id,
            defaults={"fingerprint": fingerprint},
        )
        receipt = OperationReceipt.objects.select_for_update().get(pk=receipt.pk)
        if receipt.fingerprint != fingerprint:
            raise DomainError("idempotency_mismatch", "This operation key belongs to a different request")
        principals = sorted({request.user.pk, *identity_ids})
        with connection.cursor() as cursor:
            lock = "UPDATE" if exclusive_identity else "SHARE"
            cursor.execute(f"SELECT id FROM social_identity WHERE id = ANY(%s) ORDER BY id FOR {lock}", [principals])
        if not Identity.objects.filter(pk=request.user.pk, retired_at=None).exists():
            raise DomainError("identity_retired", "This identity is retired", 401)
        scopes = set(scope_ids)
        if include_membership_scopes:
            scopes.update(GroupMembership.objects.filter(identity=request.user, removed_at=None, group__deleted_at=None).values_list("group__scope_id", flat=True))
        list(SyncScope.objects.select_for_update().filter(pk__in=scopes).order_by("id"))
        if receipt.completed_at and receipt.completed_at < timezone.now() - timedelta(days=30):
            raise DomainError("operation_completed", "This operation already completed; synchronize to retrieve current state")
        yield receipt
        if receipt.completed_at is None and not reserve:
            if receipt.status is None:
                raise RuntimeError("Mutation did not record its result")
            receipt.response = json.loads(json.dumps(receipt.response, cls=DjangoJSONEncoder))
            receipt.completed_at = timezone.now()
            receipt.save(update_fields=["status", "response", "completed_at"])


def accept_saved_state(instance, saved_at, operation_id, current):
    if (saved_at, operation_id.int) <= (instance.saved_at, instance.save_id.int):
        raise DomainError("superseded", "A newer saved version is available", current=current)
    instance.saved_at = saved_at
    instance.save_id = operation_id
    instance.updated_at = timezone.now()
