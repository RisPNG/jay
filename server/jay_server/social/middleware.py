import json
import logging
import time
from uuid import uuid4

from asgiref.sync import markcoroutinefunction


logger = logging.getLogger("jay.requests")


class RequestLogMiddleware:
    async_capable = True
    sync_capable = False

    def __init__(self, get_response):
        self.get_response = get_response
        markcoroutinefunction(self)

    async def __call__(self, request):
        request_id = str(uuid4())
        started = time.monotonic()
        response = await self.get_response(request)
        response["X-Request-ID"] = request_id
        logger.info(json.dumps({
            "request_id": request_id, "operation_id": request.headers.get("Idempotency-Key"),
            "method": request.method, "path": request.path, "status": response.status_code,
            "duration_ms": round((time.monotonic() - started) * 1000, 2),
        }, separators=(",", ":")))
        return response
