import asyncio
import json
import logging
import random
import signal
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import suppress
from datetime import timedelta
from uuid import uuid4

from channels.db import database_sync_to_async
from django.conf import settings
from django.db import close_old_connections, transaction
from django.db.models import Exists, OuterRef, Q
from django.utils import timezone

from .access import identity_capabilities, require_membership
from .alarms import record_alarm_response, schedule_occurrences
from .api.representations import AlarmRepresentation, SoundRepresentation
from .errors import DomainError
from .groups import publish_membership, remove_membership
from .models import AlarmOccurrence, DeliveryWork, Group, GroupMembership, Identity, OperationReceipt, PushSubscription, SharedAlarm, SharedSound, SharedTimer, SyncScope, SyncVersion, WorkerHeartbeat
from .providers import object_storage_client, validate_sound_object
from .synchronization import publish_changes


logger = logging.getLogger(__name__)


def claim_work(kind, owner):
    with transaction.atomic():
        now = timezone.now()
        work = DeliveryWork.objects.select_for_update(skip_locked=True).filter(kind=kind, available_at__lte=now, failed_at=None).filter(Q(lease_until=None) | Q(lease_until__lt=now)).order_by("available_at", "id").first()
        if work is None:
            return None
        work.lease_owner, work.lease_until = owner, now + timedelta(seconds=60)
        work.attempts += 1
        work.save(update_fields=["lease_owner", "lease_until", "attempts"])
        return work


def finish_work(work, owner, complete=True, error=None):
    with transaction.atomic():
        owned = DeliveryWork.objects.filter(pk=work.pk, lease_owner=owner)
        current = owned.select_for_update().first()
        if current is None:
            return
        if work.kind == "push" and current.payload.get("revision") != work.payload.get("revision"):
            owned.update(lease_owner=None, lease_until=None, available_at=timezone.now(), failed_at=None)
            return
        if error is not None:
            terminal = work.created_at < timezone.now() - timedelta(hours=24)
            code = error.domain_code if isinstance(error, DomainError) else type(error).__name__
            owned.update(
                lease_owner=None, lease_until=None, error_code=code,
                available_at=timezone.now() + timedelta(seconds=min(300, 2 ** min(work.attempts - 1, 9)) * random.uniform(0.8, 1.2)),
                failed_at=timezone.now() if terminal else None,
            )
            logger.warning("worker_attempt_failed kind=%s work_id=%s code=%s terminal=%s", work.kind, work.pk, code, terminal)
        elif complete:
            owned.delete()
        else:
            owned.update(payload=work.payload, lease_owner=None, lease_until=None, available_at=timezone.now())


def process_rescheduling(work):
    payload = work.payload
    alarms = SharedAlarm.objects.select_related("group").filter(deleted_at=None, group__deleted_at=None).order_by("id")
    if payload.get("alarm_id"):
        alarms = alarms.filter(pk=payload["alarm_id"])
    if payload.get("group_id"):
        alarms = alarms.filter(group_id=payload["group_id"])
    if payload.get("identity_id"):
        alarms = alarms.filter(group__memberships__identity_id=payload["identity_id"], group__memberships__removed_at=None)
    if payload.get("after_alarm"):
        alarms = alarms.filter(pk__gt=payload["after_alarm"])
    alarm = alarms.first()
    if alarm is None:
        return True
    with transaction.atomic():
        SyncScope.objects.select_for_update().get(pk=alarm.group.scope_id)
        alarm = SharedAlarm.objects.select_for_update().select_related("group").get(pk=alarm.pk)
        members = GroupMembership.objects.filter(group=alarm.group, removed_at=None, identity__retired_at=None).order_by("identity_id")
        if payload.get("identity_id"):
            members = members.filter(identity_id=payload["identity_id"])
        if payload.get("after_identity"):
            members = members.filter(identity_id__gt=payload["after_identity"])
        ids = list(members.values_list("identity_id", flat=True)[:101])
        changes = schedule_occurrences(alarm, ids[:100])
        changes.append(("alarm", str(alarm.pk), "upsert", AlarmRepresentation(alarm).data, None))
        publish_changes(alarm.group.scope_id, changes, group=alarm.group)
        if len(ids) > 100:
            payload["after_identity"] = ids[99]
        else:
            payload.pop("after_identity", None)
            payload["after_alarm"] = str(alarm.pk)
            if payload.get("alarm_id"):
                return True
    return False


def process_deadlines():
    progressed = False
    candidates = list(AlarmOccurrence.objects.filter(state="pending", deadline_at__lte=timezone.now()).order_by("deadline_at").values_list("group__scope_id", flat=True)[:1000])
    if not candidates:
        return False
    with transaction.atomic():
        scope = SyncScope.objects.select_for_update(skip_locked=True).filter(pk__in=set(candidates)).order_by("id").first()
        if scope is None:
            return False
        group = Group.objects.select_for_update().get(scope=scope)
        due = list(AlarmOccurrence.objects.filter(group=group, state="pending", deadline_at__lte=timezone.now()).select_related("identity").order_by("deadline_at")[:100])
        for occurrence in due:
            occurrence.refresh_from_db()
            if occurrence.state != "pending":
                continue
            alarm = SharedAlarm.objects.select_for_update().select_related("group").get(pk=occurrence.alarm_id)
            if group.deleted_at or alarm.deleted_at or not alarm.enabled or alarm.revision != occurrence.alarm_revision or occurrence.identity.retired_at or not GroupMembership.objects.filter(group=group, identity=occurrence.identity, removed_at=None).exists():
                occurrence.state, occurrence.resolved_at = "canceled", timezone.now()
                occurrence.save(update_fields=["state", "resolved_at"])
                progressed = True
                continue
            if DeliveryWork.objects.filter(kind="reschedule", failed_at=None).filter(Q(payload__alarm_id=str(alarm.pk)) | Q(payload__group_id=str(group.pk))).exists():
                continue
            record_alarm_response(alarm, occurrence.identity, {"id": uuid4(), "alarm_revision": alarm.revision, "kind": "ignored", "occurred_at": occurrence.deadline_at, "occurrence_key": occurrence.occurrence_key, "reason": "deadline"})
            progressed = True
    return progressed


def process_sound_verification(work, owner, stopping=None):
    sound = SharedSound.objects.select_related("group", "uploaded_by").filter(pk=work.payload["sound_id"], status="verifying").first()
    if sound is None:
        return True
    failure = None
    try:
        object_storage_client().copy_object(Bucket=settings.B2_BUCKET_NAME, Key=sound.object_key, CopySource={"Bucket": settings.B2_BUCKET_NAME, "Key": sound.staging_key})
        validate_sound_object(sound, stopping)
    except DomainError as error:
        if error.status_code >= 500:
            raise
        failure = error.domain_code
    with transaction.atomic():
        SyncScope.objects.select_for_update().get(pk=sound.group.scope_id)
        sound = SharedSound.objects.select_for_update().select_related("group", "uploaded_by").get(pk=sound.pk)
        if not DeliveryWork.objects.filter(pk=work.pk, lease_owner=owner, lease_until__gt=timezone.now()).exists():
            return False
        if sound.status != "verifying":
            return True
        try:
            if sound.uploaded_by is None or sound.uploaded_by.retired_at:
                raise DomainError("uploader_removed", "Uploader is no longer active", 403)
            require_membership(sound.group, sound.uploaded_by, editor=True)
            if not identity_capabilities(sound.uploaded_by)["shared_sound_upload"]:
                raise DomainError("entitlement_required", "Entitlement expired", 403)
        except DomainError as error:
            failure = error.domain_code
        sound.status = "failed" if failure else "ready"
        sound.failure_code = failure
        sound.ready_at = None if failure else timezone.now()
        sound.save(update_fields=["status", "failure_code", "ready_at"])
        publish_changes(sound.group.scope_id, [("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None)], group=sound.group)
        for key in ([sound.staging_key, sound.object_key] if failure else [sound.staging_key]):
            DeliveryWork.objects.get_or_create(deduplication_key=f"delete_sound:{key}", defaults={"kind": "delete_sound", "payload": {"object_key": key}, "available_at": timezone.now() + timedelta(minutes=15)})
    return True


def process_group_cleanup(work):
    group = Group.objects.filter(pk=work.payload["group_id"], deleted_at__isnull=False).first()
    if group is None:
        return True
    member = GroupMembership.objects.select_related("identity", "group").filter(group=group, removed_at=None).order_by("id").first()
    if member:
        with transaction.atomic():
            list(SyncScope.objects.select_for_update().filter(pk__in=[group.scope_id, member.identity.scope_id]).order_by("id"))
            member.removed_at = timezone.now()
            member.save(update_fields=["removed_at"])
            publish_membership(member, None, "delete")
        return False
    with transaction.atomic():
        SyncScope.objects.select_for_update().get(pk=group.scope_id)
        SharedAlarm.objects.filter(group=group, deleted_at=None).update(deleted_at=group.deleted_at)
        SharedTimer.objects.filter(group=group, deleted_at=None).update(deleted_at=group.deleted_at)
        AlarmOccurrence.objects.filter(group=group, state="pending").update(state="canceled", resolved_at=timezone.now())
        SharedSound.objects.filter(group=group).delete()
    return True


def process_identity_retirement(work):
    identity = Identity.objects.get(pk=work.payload["identity_id"])
    member = GroupMembership.objects.select_related("group", "identity").filter(identity=identity, removed_at=None).order_by("group_id").first()
    if member is None:
        return True
    with transaction.atomic():
        list(SyncScope.objects.select_for_update().filter(pk__in=[identity.scope_id, member.group.scope_id]).order_by("id"))
        group = Group.objects.select_for_update().get(pk=member.group_id)
        if member.role == "leader" and not GroupMembership.objects.filter(group=group, role="leader", removed_at=None).exclude(identity=identity).exists():
            group.deleted_at = timezone.now()
            group.save(update_fields=["deleted_at"])
            DeliveryWork.objects.get_or_create(deduplication_key=f"delete_group:{group.pk}", defaults={"kind": "delete_group", "payload": {"group_id": str(group.pk)}})
            member.removed_at = timezone.now()
            member.save(update_fields=["removed_at"])
            publish_membership(member, identity, "delete")
        else:
            remove_membership(member, identity)
    return False


def maintain_state():
    now = timezone.now()
    if settings.IDENTITY_INACTIVITY_TIMEOUT_DAYS:
        stale = Identity.objects.filter(retired_at=None, last_seen_at__lt=now - timedelta(days=settings.IDENTITY_INACTIVITY_TIMEOUT_DAYS)).order_by("last_seen_at")[:50]
        for identity in stale:
            with transaction.atomic():
                identity = Identity.objects.select_for_update().get(pk=identity.pk)
                if identity.retired_at or identity.last_seen_at >= now - timedelta(days=settings.IDENTITY_INACTIVITY_TIMEOUT_DAYS):
                    continue
                identity.retired_at = now
                identity.save(update_fields=["retired_at"])
                PushSubscription.objects.filter(identity=identity).delete()
                DeliveryWork.objects.get_or_create(deduplication_key=f"retire:{identity.pk}", defaults={"kind": "retire", "payload": {"identity_id": identity.pk}})
    for timer in SharedTimer.objects.select_related("group").filter(deleted_at=None, expires_at__lt=now - timedelta(minutes=15))[:50]:
        with transaction.atomic():
            SyncScope.objects.select_for_update().get(pk=timer.group.scope_id)
            timer = SharedTimer.objects.select_for_update().select_related("group").get(pk=timer.pk)
            if timer.deleted_at or timer.expires_at >= now - timedelta(minutes=15):
                continue
            timer.deleted_at = now
            timer.save(update_fields=["deleted_at"])
            publish_changes(timer.group.scope_id, [("timer", str(timer.pk), "delete", {"id": str(timer.pk)}, None)], group=timer.group)
    for sound in SharedSound.objects.select_related("group").filter(status="pending", created_at__lt=now - timedelta(hours=24))[:20]:
        with transaction.atomic():
            SyncScope.objects.select_for_update().get(pk=sound.group.scope_id)
            sound = SharedSound.objects.select_for_update().select_related("group").get(pk=sound.pk)
            if sound.status != "pending":
                continue
            sound.status, sound.failure_code = "failed", "upload_expired"
            sound.save(update_fields=["status", "failure_code"])
            publish_changes(sound.group.scope_id, [("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None)], group=sound.group)
            for key in [sound.object_key, sound.staging_key]:
                DeliveryWork.objects.get_or_create(deduplication_key=f"delete_sound:{key}", defaults={"kind": "delete_sound", "payload": {"object_key": key}})
    cutoff = now - timedelta(days=settings.SYNC_RETENTION_DAYS)
    unused = SharedSound.objects.filter(created_at__lt=now - timedelta(hours=24)).exclude(status="verifying").annotate(
        has_alarm=Exists(SharedAlarm.objects.filter(sound_id=OuterRef("pk"), deleted_at=None)),
        has_timer=Exists(SharedTimer.objects.filter(sound_id=OuterRef("pk"), deleted_at=None)),
    ).filter(has_alarm=False, has_timer=False).select_related("group")[:20]
    for sound in unused:
        with transaction.atomic():
            SyncScope.objects.select_for_update().get(pk=sound.group.scope_id)
            sound = SharedSound.objects.select_for_update().filter(pk=sound.pk).first()
            if sound is None or sound.status == "verifying" or SharedAlarm.objects.filter(sound=sound, deleted_at=None).exists() or SharedTimer.objects.filter(sound=sound, deleted_at=None).exists():
                continue
            publish_changes(sound.group.scope_id, [("sound", str(sound.pk), "delete", {"id": str(sound.pk)}, None)], group=sound.group)
            sound.delete()
    expired_versions = Q(superseded_at__lt=cutoff) | Q(kind__in=["activity", "outcome"], created_at__lt=cutoff)
    candidates = SyncVersion.objects.filter(expired_versions).values_list("scope_id", flat=True)[:50]
    for scope_id in set(candidates):
        with transaction.atomic():
            scope = SyncScope.objects.select_for_update().get(pk=scope_id)
            obsolete = SyncVersion.objects.filter(expired_versions, scope=scope)
            largest = obsolete.order_by("-revision").values_list("revision", flat=True).first()
            if largest is not None:
                scope.retention_floor = max(scope.retention_floor, largest)
                scope.save(update_fields=["retention_floor"])
                obsolete.delete()
    OperationReceipt.objects.filter(completed_at__lt=cutoff).exclude(response=None).update(response=None)
    for model in [SharedAlarm, SharedTimer]:
        for resource in model.objects.filter(deleted_at__lt=cutoff).select_related("group")[:20]:
            with transaction.atomic():
                SyncScope.objects.select_for_update().get(pk=resource.group.scope_id)
                model.objects.filter(pk=resource.pk, deleted_at__lt=cutoff).delete()
    for group in Group.objects.filter(deleted_at__lt=cutoff)[:20]:
        with transaction.atomic():
            SyncScope.objects.select_for_update().get(pk=group.scope_id)
            if GroupMembership.objects.filter(group=group, removed_at=None).exists():
                continue
            Group.objects.filter(pk=group.pk, deleted_at__lt=cutoff).delete()
            SyncVersion.objects.filter(scope_id=group.scope_id).delete()
    for identity in Identity.objects.filter(retired_at__lt=cutoff).exclude(name="Removed member")[:20]:
        with transaction.atomic():
            identity = Identity.objects.select_for_update().get(pk=identity.pk)
            if GroupMembership.objects.filter(identity=identity, removed_at=None).exists():
                continue
            SyncScope.objects.select_for_update().get(pk=identity.scope_id)
            identity.name, identity.token_hash, identity.time_zone = "Removed member", b"", "UTC"
            identity.save(update_fields=["name", "token_hash", "time_zone"])
            SyncVersion.objects.filter(scope_id=identity.scope_id).delete()
    WorkerHeartbeat.objects.filter(progressed_at__lt=now - timedelta(days=1)).delete()


def push_recipients(work):
    subscriptions = PushSubscription.objects.filter(identity__retired_at=None).order_by("token")
    if work.payload.get("identity_id"):
        subscriptions = subscriptions.filter(identity_id=work.payload["identity_id"])
    else:
        subscriptions = subscriptions.filter(identity__memberships__group_id=work.payload["group_id"], identity__memberships__removed_at=None)
        if work.payload.get("excluding"):
            subscriptions = subscriptions.exclude(identity_id=work.payload["excluding"])
    if work.payload.get("after_token"):
        subscriptions = subscriptions.filter(token__gt=work.payload["after_token"])
    return list(subscriptions.values_list("token", flat=True)[:101])


class Worker:
    def __init__(self):
        self.owner = uuid4()
        self.stop = asyncio.Event()
        self.stopping = threading.Event()
        self.heartbeats = {}
        self.database_executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="jay-worker-db")
        self.deadline_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="jay-deadlines")
        self.maintenance_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="jay-maintenance")
        self.verification_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="jay-verification")
        self.deletion_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="jay-deletion")

    async def run(self):
        loop = asyncio.get_running_loop()
        for signum in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(signum, self.stop.set)
        tasks = [asyncio.create_task(self.deadlines()), asyncio.create_task(self.maintenance()), asyncio.create_task(self.provider("verify")), asyncio.create_task(self.provider("delete_sound"))]
        tasks.extend(asyncio.create_task(self.provider("push")) for _ in range(4))
        stopped = asyncio.create_task(self.stop.wait())
        try:
            done, _ = await asyncio.wait([*tasks, stopped], return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                if task is not stopped:
                    task.result()
            self.stop.set()
            self.stopping.set()
            await asyncio.wait_for(asyncio.gather(*tasks), timeout=25)
        finally:
            self.stopping.set()
            stopped.cancel()
            for task in tasks:
                task.cancel()
            await asyncio.gather(stopped, *tasks, return_exceptions=True)
            await database_sync_to_async(WorkerHeartbeat.objects.filter(instance_id=self.owner).delete, thread_sensitive=False, executor=self.database_executor)()
            for executor in [self.database_executor, self.deadline_executor, self.maintenance_executor, self.verification_executor, self.deletion_executor]:
                executor.shutdown(wait=True, cancel_futures=True)

    async def heartbeat(self, kind):
        now = asyncio.get_running_loop().time()
        if now - self.heartbeats.get(kind, 0) < 10:
            return
        self.heartbeats[kind] = now
        await database_sync_to_async(WorkerHeartbeat.objects.update_or_create, thread_sensitive=False, executor=self.database_executor)(instance_id=self.owner, work_class=kind, defaults={"progressed_at": timezone.now(), "error_code": None})

    async def deadlines(self):
        while not self.stop.is_set():
            await self.heartbeat("deadlines")
            work = await database_sync_to_async(claim_work, thread_sensitive=False, executor=self.deadline_executor)("reschedule", self.owner)
            if work:
                try:
                    complete = await database_sync_to_async(process_rescheduling, thread_sensitive=False, executor=self.deadline_executor)(work)
                except Exception as error:
                    await database_sync_to_async(finish_work, thread_sensitive=False, executor=self.deadline_executor)(work, self.owner, error=error)
                else:
                    await database_sync_to_async(finish_work, thread_sensitive=False, executor=self.deadline_executor)(work, self.owner, complete)
            progressed = await database_sync_to_async(process_deadlines, thread_sensitive=False, executor=self.deadline_executor)()
            if not work and not progressed:
                await asyncio.sleep(0.25)

    async def maintenance(self):
        last_sweep = 0
        while not self.stop.is_set():
            await self.heartbeat("maintenance")
            if asyncio.get_running_loop().time() - last_sweep >= 60:
                await database_sync_to_async(maintain_state, thread_sensitive=False, executor=self.maintenance_executor)()
                last_sweep = asyncio.get_running_loop().time()
            for kind, operation in [("retire", process_identity_retirement), ("delete_group", process_group_cleanup)]:
                work = await database_sync_to_async(claim_work, thread_sensitive=False, executor=self.maintenance_executor)(kind, self.owner)
                if work:
                    try:
                        complete = await database_sync_to_async(operation, thread_sensitive=False, executor=self.maintenance_executor)(work)
                    except Exception as error:
                        await database_sync_to_async(finish_work, thread_sensitive=False, executor=self.maintenance_executor)(work, self.owner, error=error)
                    else:
                        await database_sync_to_async(finish_work, thread_sensitive=False, executor=self.maintenance_executor)(work, self.owner, complete)
            await asyncio.sleep(1)

    async def renew_lease(self, work):
        while True:
            await asyncio.sleep(10)
            await self.heartbeat(work.kind)
            await database_sync_to_async(DeliveryWork.objects.filter(pk=work.pk, lease_owner=self.owner).update, thread_sensitive=False, executor=self.database_executor)(lease_until=timezone.now() + timedelta(seconds=60))

    async def provider(self, kind):
        while not self.stop.is_set():
            await self.heartbeat(kind)
            work = await database_sync_to_async(claim_work, thread_sensitive=False, executor=self.database_executor)(kind, self.owner)
            if work is None:
                await asyncio.sleep(0.25)
                continue
            renewal = asyncio.create_task(self.renew_lease(work))
            try:
                complete = True
                if kind == "verify":
                    complete = await database_sync_to_async(process_sound_verification, thread_sensitive=False, executor=self.verification_executor)(work, self.owner, self.stopping)
                elif kind == "delete_sound":
                    client = await asyncio.get_running_loop().run_in_executor(self.deletion_executor, object_storage_client)
                    await asyncio.get_running_loop().run_in_executor(self.deletion_executor, lambda: client.delete_object(Bucket=settings.B2_BUCKET_NAME, Key=work.payload["object_key"]))
                elif settings.FIREBASE_CREDENTIALS_JSON:
                    import firebase_admin
                    from firebase_admin import credentials, messaging

                    try:
                        firebase_admin.get_app()
                    except ValueError:
                        firebase_admin.initialize_app(credentials.Certificate(json.loads(settings.FIREBASE_CREDENTIALS_JSON)), options={"httpTimeout": 20})
                    tokens = await database_sync_to_async(push_recipients, thread_sensitive=False, executor=self.database_executor)(work)
                    if tokens:
                        response = await asyncio.wait_for(messaging.send_each_async([messaging.Message(token=token, data={"kind": "sync", "scope_id": work.payload["scope_id"]}, android=messaging.AndroidConfig(priority="high")) for token in tokens[:100]]), timeout=20)
                        retry = False
                        for token, result in zip(tokens[:100], response.responses):
                            if result.exception:
                                if isinstance(result.exception, messaging.UnregisteredError):
                                    await database_sync_to_async(PushSubscription.objects.filter(token=token).delete, thread_sensitive=False, executor=self.database_executor)()
                                else:
                                    retry = True
                        if retry:
                            raise DomainError("push_unavailable", "A push delivery needs retry", 503)
                        if len(tokens) > 100:
                            work.payload["after_token"] = tokens[99]
                            complete = False
            except Exception as error:
                await database_sync_to_async(finish_work, thread_sensitive=False, executor=self.database_executor)(work, self.owner, error=error)
            else:
                await database_sync_to_async(finish_work, thread_sensitive=False, executor=self.database_executor)(work, self.owner, complete)
            finally:
                renewal.cancel()
                with suppress(asyncio.CancelledError):
                    await renewal
