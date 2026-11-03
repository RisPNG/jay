import os

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "jay_server.settings")

from django.core.asgi import get_asgi_application

django_application = get_asgi_application()

from channels.routing import URLRouter
from django.conf import settings
from django.urls import path, re_path

from .social.live import EventConsumer, GroupRingConsumer, LiveBroker


class Application:
    def __init__(self):
        self.broker = LiveBroker()
        self.active_requests = 0
        self.live_routes = [path("v1/events", EventConsumer.as_asgi()), path("v1/groups/<uuid:group_id>/ring", GroupRingConsumer.as_asgi())]
        self.router = URLRouter([*self.live_routes, re_path(r"", django_application)])

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            while True:
                event = await receive()
                if event["type"] == "lifespan.startup":
                    await self.broker.start()
                    await send({"type": "lifespan.startup.complete"})
                elif event["type"] == "lifespan.shutdown":
                    await self.broker.stop()
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        if scope["type"] != "http":
            return
        request_path = scope.get("path_remaining", scope["path"].removeprefix(scope.get("root_path", ""))).lstrip("/")
        if any(route.pattern.match(request_path) for route in self.live_routes):
            await self.router({**scope, "jay.broker": self.broker}, receive, send)
            return
        if self.active_requests >= settings.MAX_HTTP_REQUESTS:
            await send({"type": "http.response.start", "status": 503, "headers": [(b"retry-after", b"1"), (b"content-type", b"application/json")]})
            await send({"type": "http.response.body", "body": b'{"code":"busy","detail":"Request capacity reached","errors":[]}'})
            return
        self.active_requests += 1
        try:
            body = bytearray()
            while True:
                event = await receive()
                if event["type"] == "http.disconnect":
                    return
                body.extend(event.get("body", b""))
                if len(body) > settings.DATA_UPLOAD_MAX_MEMORY_SIZE:
                    await send({"type": "http.response.start", "status": 413, "headers": [(b"content-type", b"application/json")]})
                    await send({"type": "http.response.body", "body": b'{"code":"too_large","detail":"Request exceeds 256 KiB","errors":[]}'})
                    return
                if not event.get("more_body", False):
                    break
            first = True

            async def receive_request():
                nonlocal first
                if first:
                    first = False
                    return {"type": "http.request", "body": bytes(body), "more_body": False}
                return await receive()

            await self.router(scope, receive_request, send)
        finally:
            self.active_requests -= 1


application = Application()
