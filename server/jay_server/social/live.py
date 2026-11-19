import asyncio
import json
import logging
import re
from contextlib import suppress
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import UTC, timedelta
from urllib.parse import unquote_plus
from uuid import UUID, uuid4

from channels.consumer import AsyncConsumer
from channels.db import database_sync_to_async
from channels.exceptions import StopConsumer
from django.conf import settings
from django.db import DatabaseError, connection, transaction
from django.http.request import split_domain_port, validate_host
from django.utils import timezone
from psycopg import AsyncConnection
from rest_framework.exceptions import APIException
from rest_framework.fields import DateTimeField

from .authentication import authenticate_identity
from .access import require_membership
from .errors import DomainError
from .models import DeliveryWork, Group, GroupMembership
from .synchronization import publish_changes


logger = logging.getLogger(__name__)
MAX_RING_LABEL_LENGTH = 80


@dataclass(eq=False)
class Subscription:
    identity_id: str
    identity_scope: str
    scopes: set[str]
    wake: asyncio.Event = field(default_factory=asyncio.Event)
    dirty: set[str] = field(default_factory=set)
    all_scopes: bool = True
    rings: dict = field(default_factory=dict)
    reset_rings: bool = False


def authorize_stream(authorization, identity_id):
    identity = authenticate_identity(authorization, identity_id)
    scopes = {str(identity.scope_id)}
    scopes.update(str(scope) for scope in GroupMembership.objects.filter(identity=identity, removed_at=None, group__deleted_at=None).values_list("group__scope_id", flat=True))
    return identity.pk, str(identity.scope_id), scopes


@transaction.atomic
def publish_group_ring(authorization, identity_id, group_id, session_id, scope_id=None, *, initial=False, origin=None, push_token_digest=None, label=None):
    lease_seconds = 5
    if scope_id is None:
        identity = authenticate_identity(authorization, identity_id)
        group = Group.objects.filter(pk=group_id, deleted_at=None).first()
        if group is None:
            raise DomainError("not_found", "Group not found", 404)
        require_membership(group, identity, editor=True)
        scope_id = str(group.scope_id)
        if initial:
            publish_changes(scope_id, [], group=group, actor=identity, push_sync=False, activity={
                "entity_type": "ring", "entity_id": session_id, "action": "rang", "entity_label": label,
            })
        active = True
    else:
        active = False
    payload = {"scope_id": scope_id, "group_id": str(group_id), "session_id": session_id}
    if active:
        expires_at = DateTimeField(format="iso-8601", default_timezone=UTC).to_representation(timezone.now() + timedelta(seconds=lease_seconds))
        payload.update(actor_id=identity.pk, expires_at=expires_at)
        if origin is not None:
            payload["origin"] = origin
        if initial and settings.FIREBASE_CREDENTIALS_JSON:
            wake_payload = {"kind": "ring", **payload}
            if push_token_digest is not None:
                wake_payload["origin_push_digest"] = push_token_digest
            DeliveryWork.objects.create(kind="push", deduplication_key=f"ring:{session_id}", payload=wake_payload)
        payload.update(actor_label=identity.name, group_label=group.name)
        if label is not None:
            payload["ring_label"] = label
    else:
        DeliveryWork.objects.filter(deduplication_key=f"ring:{session_id}").delete()
    payload.update(active=active, lease_seconds=lease_seconds)
    with connection.cursor() as cursor:
        cursor.execute("SELECT pg_notify('jay_rings', %s)", [json.dumps(payload, separators=(",", ":"))])
    return scope_id


class LiveBroker:
    def __init__(self):
        self.subscriptions = set()
        self.scopes = {}
        self.listener = None
        self.ready = asyncio.Event()
        self.authenticating = 0
        self.ringers = {}
        self.executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="jay-live-auth")

    async def start(self):
        self.listener = asyncio.create_task(self.listen())

    async def stop(self):
        ringers = [task for task in self.ringers.values() if task is not None]
        for task in ringers:
            task.cancel()
        if ringers:
            await asyncio.gather(*ringers, return_exceptions=True)
        if self.listener:
            self.listener.cancel()
            with suppress(asyncio.CancelledError):
                await self.listener
        self.executor.shutdown(wait=True)

    async def listen(self):
        config = settings.DATABASES["default"]
        parameters = {key: config[name] for key, name in [("dbname", "NAME"), ("user", "USER"), ("password", "PASSWORD"), ("host", "HOST"), ("port", "PORT")] if config.get(name)}
        parameters.update({key: value for key, value in config.get("OPTIONS", {}).items() if key not in {"pool", "options"}})
        while True:
            try:
                async with await AsyncConnection.connect(**parameters, autocommit=True) as connection:
                    await connection.execute("LISTEN jay_changes")
                    await connection.execute("LISTEN jay_rings")
                    self.ready.set()
                    for subscription in self.subscriptions:
                        subscription.all_scopes = True
                        subscription.reset_rings = True
                        subscription.wake.set()
                    async for notification in connection.notifies():
                        if notification.channel == "jay_rings":
                            ring = json.loads(notification.payload)
                            for subscription in tuple(self.scopes.get(ring["scope_id"], ())):
                                subscription.rings[ring["session_id"]] = ring
                                if len(subscription.rings) > 64:
                                    subscription.rings.clear()
                                    subscription.reset_rings = True
                                subscription.wake.set()
                            continue
                        for subscription in tuple(self.scopes.get(notification.payload, ())):
                            if not subscription.all_scopes:
                                subscription.dirty.add(notification.payload)
                                if len(subscription.dirty) > 64:
                                    subscription.dirty.clear()
                                    subscription.all_scopes = True
                            subscription.wake.set()
            except asyncio.CancelledError:
                raise
            except Exception:
                self.ready.clear()
                logger.warning("live_listener_disconnected")
                await asyncio.sleep(2)


class EventConsumer(AsyncConsumer):
    async def http_request(self, event):
        if hasattr(self, "subscription"):
            return
        headers = {key.lower(): value.decode("latin1") for key, value in self.scope["headers"]}
        domain, _ = split_domain_port(headers.get(b"host", ""))
        if self.scope["method"] != "GET" or event.get("body") or not validate_host(domain, settings.ALLOWED_HOSTS):
            await self.send({"type": "http.response.start", "status": 400, "headers": []})
            await self.send({"type": "http.response.body", "body": b"Invalid stream request"})
            raise StopConsumer()
        self.broker = self.scope["jay.broker"]
        if len(self.broker.subscriptions) + len(self.broker.ringers) >= settings.MAX_EVENT_CONNECTIONS or self.broker.authenticating >= 64:
            await self.send({"type": "http.response.start", "status": 503, "headers": [(b"retry-after", b"1")]})
            await self.send({"type": "http.response.body", "body": b"Stream capacity reached"})
            raise StopConsumer()
        try:
            self.broker.authenticating += 1
            identity_id, identity_scope, scopes = await database_sync_to_async(authorize_stream, thread_sensitive=False, executor=self.broker.executor)(headers.get(b"authorization", ""), headers.get(b"x-jay-identity-id", ""))
        except APIException:
            await self.send({"type": "http.response.start", "status": 401, "headers": [(b"www-authenticate", b"Bearer")]})
            await self.send({"type": "http.response.body", "body": b"Invalid identity credentials"})
            raise StopConsumer()
        except DatabaseError:
            await self.send({"type": "http.response.start", "status": 503, "headers": [(b"retry-after", b"1")]})
            await self.send({"type": "http.response.body", "body": b"Please retry the stream"})
            raise StopConsumer()
        finally:
            self.broker.authenticating -= 1
        self.authorization = headers.get(b"authorization", "")
        if len(self.broker.subscriptions) + len(self.broker.ringers) >= settings.MAX_EVENT_CONNECTIONS:
            await self.send({"type": "http.response.start", "status": 503, "headers": [(b"retry-after", b"1")]})
            await self.send({"type": "http.response.body", "body": b"Stream capacity reached"})
            raise StopConsumer()
        self.subscription = Subscription(identity_id, identity_scope, scopes)
        self.broker.subscriptions.add(self.subscription)
        for scope in scopes:
            self.broker.scopes.setdefault(scope, set()).add(self.subscription)
        await self.send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"text/event-stream"), (b"cache-control", b"no-cache, no-transform"), (b"x-accel-buffering", b"no")]})
        self.subscription.wake.set()
        self.sender = asyncio.create_task(self.send_events())

    async def send_events(self):
        authorized_at = asyncio.get_running_loop().time()
        try:
            while True:
                try:
                    await asyncio.wait_for(self.subscription.wake.wait(), timeout=15)
                except TimeoutError:
                    await asyncio.wait_for(self.send({"type": "http.response.body", "body": b": heartbeat\n\n", "more_body": True}), timeout=5)
                    if asyncio.get_running_loop().time() - authorized_at < 60:
                        continue
                self.subscription.wake.clear()
                all_scopes = self.subscription.all_scopes
                dirty = sorted(self.subscription.dirty)
                rings = list(self.subscription.rings.values())
                reset_rings = self.subscription.reset_rings
                self.subscription.rings.clear()
                self.subscription.reset_rings = False
                self.subscription.all_scopes = False
                self.subscription.dirty.clear()
                if all_scopes or rings or self.subscription.identity_scope in dirty or asyncio.get_running_loop().time() - authorized_at >= 60:
                    _, _, scopes = await database_sync_to_async(authorize_stream, thread_sensitive=False, executor=self.broker.executor)(self.authorization, self.subscription.identity_id)
                    for scope in self.subscription.scopes - scopes:
                        subscribers = self.broker.scopes.get(scope)
                        if subscribers is not None:
                            subscribers.discard(self.subscription)
                            if not subscribers:
                                self.broker.scopes.pop(scope, None)
                    for scope in scopes - self.subscription.scopes:
                        self.broker.scopes.setdefault(scope, set()).add(self.subscription)
                    self.subscription.scopes = scopes
                    authorized_at = asyncio.get_running_loop().time()
                body = "event: sync\ndata: " + json.dumps({"all": all_scopes, "scopes": dirty}, separators=(",", ":")) + "\n\n" if all_scopes or dirty else ""
                if reset_rings:
                    body += 'event: ring_reset\ndata: {}\n\n'
                for ring in rings:
                    if ring["scope_id"] in self.subscription.scopes:
                        frame = {key: value for key, value in ring.items() if key != "scope_id"}
                        if ring.get("actor_id") != self.subscription.identity_id:
                            frame.pop("origin", None)
                        body += "event: ring\ndata: " + json.dumps(frame, separators=(",", ":")) + "\n\n"
                if body:
                    await asyncio.wait_for(self.send({"type": "http.response.body", "body": body.encode(), "more_body": True}), timeout=5)
        except (APIException, DatabaseError, OSError, TimeoutError):
            with suppress(OSError, TimeoutError):
                await asyncio.wait_for(self.send({"type": "http.response.body", "body": b"", "more_body": False}), timeout=1)
        finally:
            self.broker.subscriptions.discard(self.subscription)
            for scope in self.subscription.scopes:
                subscribers = self.broker.scopes.get(scope)
                if subscribers is not None:
                    subscribers.discard(self.subscription)
                    if not subscribers:
                        self.broker.scopes.pop(scope, None)

    async def http_disconnect(self, event):
        if hasattr(self, "sender"):
            self.sender.cancel()
            with suppress(asyncio.CancelledError):
                await self.sender
        raise StopConsumer()


class GroupRingConsumer(AsyncConsumer):
    async def http_request(self, event):
        if hasattr(self, "sender"):
            return
        headers = {key.lower(): value.decode("latin1") for key, value in self.scope["headers"]}
        domain, _ = split_domain_port(headers.get(b"host", ""))
        origin_header = headers.get(b"x-jay-ring-origin")
        push_token_digest = headers.get(b"x-jay-ring-push-token-digest")
        encoded_label = headers.get(b"x-jay-ring-label", "")
        self.origin = None
        if origin_header is not None:
            with suppress(ValueError):
                self.origin = str(UUID(origin_header))
        label = None
        if len(encoded_label) <= MAX_RING_LABEL_LENGTH * 12 and re.fullmatch(
            r"(?:[A-Za-z0-9_.*~+\-]|%[0-9A-Fa-f]{2})*", encoded_label,
        ):
            with suppress(UnicodeDecodeError):
                label = unquote_plus(encoded_label, errors="strict")
        if (
            self.scope["method"] != "POST" or event.get("body") or event.get("more_body")
            or not validate_host(domain, settings.ALLOWED_HOSTS) or (origin_header is not None and self.origin is None)
            or (push_token_digest is not None and not re.fullmatch(r"[0-9a-f]{64}", push_token_digest))
            or label is None or len(label.strip()) > MAX_RING_LABEL_LENGTH or re.search(r"[\x00-\x1f\x7f-\x9f]", label)
        ):
            await self.send({"type": "http.response.start", "status": 400, "headers": []})
            await self.send({"type": "http.response.body", "body": b"Invalid ring request"})
            raise StopConsumer()
        self.broker = self.scope["jay.broker"]
        if len(self.broker.subscriptions) + len(self.broker.ringers) >= settings.MAX_EVENT_CONNECTIONS or self.broker.authenticating >= 64:
            await self.send({"type": "http.response.start", "status": 503, "headers": [(b"retry-after", b"1")]})
            await self.send({"type": "http.response.body", "body": b"Stream capacity reached"})
            raise StopConsumer()
        self.authorization = headers.get(b"authorization", "")
        self.identity_id = headers.get(b"x-jay-identity-id", "")
        self.group_id = self.scope["url_route"]["kwargs"]["group_id"]
        self.label = label.strip() or None
        self.session_id = str(uuid4())
        self.broker.ringers[self.session_id] = None
        try:
            self.broker.authenticating += 1
            self.scope_id = await database_sync_to_async(publish_group_ring, thread_sensitive=False, executor=self.broker.executor)(
                self.authorization, self.identity_id, self.group_id, self.session_id,
                initial=True, origin=self.origin, push_token_digest=push_token_digest, label=self.label,
            )
        except (APIException, DatabaseError) as error:
            self.broker.ringers.pop(self.session_id, None)
            status = error.status_code if isinstance(error, APIException) else 503
            detail = str(error) if isinstance(error, APIException) else "Please retry the ring"
            await self.send({"type": "http.response.start", "status": status, "headers": [(b"content-type", b"application/json")]})
            await self.send({"type": "http.response.body", "body": json.dumps({"code": getattr(error, "domain_code", "ring_unavailable"), "detail": detail, "errors": []}).encode()})
            raise StopConsumer()
        finally:
            self.broker.authenticating -= 1
        self.sender = asyncio.create_task(self.send_ring())
        self.broker.ringers[self.session_id] = self.sender

    async def send_ring(self):
        try:
            await self.send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"text/event-stream"), (b"cache-control", b"no-cache, no-transform"), (b"x-accel-buffering", b"no")]})
            while True:
                await asyncio.wait_for(self.send({"type": "http.response.body", "body": b": ringing\n\n", "more_body": True}), timeout=3)
                await asyncio.sleep(2)
                await database_sync_to_async(publish_group_ring, thread_sensitive=False, executor=self.broker.executor)(
                    self.authorization, self.identity_id, self.group_id, self.session_id, origin=self.origin, label=self.label,
                )
        except (APIException, DatabaseError, OSError, TimeoutError):
            with suppress(OSError, TimeoutError):
                await asyncio.wait_for(self.send({"type": "http.response.body", "body": b"", "more_body": False}), timeout=1)
        finally:
            self.broker.ringers.pop(self.session_id, None)
            with suppress(DatabaseError):
                await database_sync_to_async(publish_group_ring, thread_sensitive=False, executor=self.broker.executor)(self.authorization, self.identity_id, self.group_id, self.session_id, self.scope_id)

    async def http_disconnect(self, event):
        if hasattr(self, "sender"):
            self.sender.cancel()
            with suppress(asyncio.CancelledError):
                await self.sender
        raise StopConsumer()
