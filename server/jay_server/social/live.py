import asyncio
import json
import logging
from contextlib import suppress
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from channels.consumer import AsyncConsumer
from channels.db import database_sync_to_async
from channels.exceptions import StopConsumer
from django.conf import settings
from django.db import DatabaseError
from django.http.request import split_domain_port, validate_host
from psycopg import AsyncConnection
from rest_framework.exceptions import APIException

from .authentication import authenticate_identity
from .models import GroupMembership, Identity


logger = logging.getLogger(__name__)


@dataclass(eq=False)
class Subscription:
    identity_id: str
    identity_scope: str
    scopes: set[str]
    wake: asyncio.Event = field(default_factory=asyncio.Event)
    dirty: set[str] = field(default_factory=set)
    all_scopes: bool = True


def authorize_stream(authorization, identity_id):
    identity = authenticate_identity(authorization, identity_id)
    scopes = {str(identity.scope_id)}
    scopes.update(str(scope) for scope in GroupMembership.objects.filter(identity=identity, removed_at=None, group__deleted_at=None).values_list("group__scope_id", flat=True))
    return identity.pk, str(identity.scope_id), scopes


class LiveBroker:
    def __init__(self):
        self.subscriptions = set()
        self.scopes = {}
        self.listener = None
        self.ready = asyncio.Event()
        self.authenticating = 0
        self.executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="jay-live-auth")

    async def start(self):
        self.listener = asyncio.create_task(self.listen())

    async def stop(self):
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
                    self.ready.set()
                    for subscription in self.subscriptions:
                        subscription.all_scopes = True
                        subscription.wake.set()
                    async for notification in connection.notifies():
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
        if len(self.broker.subscriptions) >= settings.MAX_EVENT_CONNECTIONS or self.broker.authenticating >= 64:
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
        if len(self.broker.subscriptions) >= settings.MAX_EVENT_CONNECTIONS:
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
                self.subscription.all_scopes = False
                self.subscription.dirty.clear()
                if all_scopes or self.subscription.identity_scope in dirty or asyncio.get_running_loop().time() - authorized_at >= 60:
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
                body = "event: sync\ndata: " + json.dumps({"all": all_scopes, "scopes": dirty}, separators=(",", ":")) + "\n\n"
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
