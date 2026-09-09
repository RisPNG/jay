from django.db import DatabaseError, IntegrityError, OperationalError
from psycopg_pool import PoolTimeout
from rest_framework.exceptions import APIException
from rest_framework.response import Response
from rest_framework.views import exception_handler


class DomainError(APIException):
    def __init__(self, code, detail, status=409, current=None):
        self.status_code = status
        self.domain_code = code
        self.current = current
        super().__init__(detail, code=code)


def api_exception_handler(exception, context):
    if isinstance(exception, DomainError):
        data = {"code": exception.domain_code, "detail": str(exception.detail), "errors": []}
        if exception.current is not None:
            data["current"] = exception.current
        return Response(data, status=exception.status_code)
    if isinstance(exception, IntegrityError):
        return Response({"code": "conflict", "detail": "The operation conflicts with existing state", "errors": []}, status=409)
    if isinstance(exception, (OperationalError, PoolTimeout)):
        return Response({"code": "busy", "detail": "Please retry this operation", "errors": []}, status=503, headers={"Retry-After": "1"})
    if isinstance(exception, DatabaseError):
        cause = exception.__cause__
        if getattr(cause, "sqlstate", None) in {"55P03", "57014", "40P01", "40001"}:
            return Response({"code": "busy", "detail": "Please retry this operation", "errors": []}, status=503, headers={"Retry-After": "1"})
    response = exception_handler(exception, context)
    if response is None:
        return None
    errors = []
    pending = [("", response.data)]
    while pending:
        path, value = pending.pop(0)
        if isinstance(value, dict):
            pending.extend((f"{path}.{key}".lstrip("."), item) for key, item in value.items())
        elif isinstance(value, list):
            pending.extend((path, item) for item in value)
        else:
            errors.append({"path": path, "code": getattr(value, "code", "invalid"), "message": str(value)})
    response.data = {
        "code": errors[0]["code"] if errors else "invalid",
        "detail": errors[0]["message"] if errors else "Invalid request",
        "errors": errors,
    }
    return response
