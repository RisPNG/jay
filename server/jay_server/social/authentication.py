import hashlib
import hmac
from datetime import timedelta

from django.utils import timezone
from rest_framework.authentication import BaseAuthentication
from rest_framework.exceptions import AuthenticationFailed
from rest_framework.throttling import SimpleRateThrottle

from .models import Identity


def authenticate_identity(authorization, identity_id):
    scheme, separator, token = authorization.partition(" ")
    if separator != " " or scheme.lower() != "bearer":
        raise AuthenticationFailed("Invalid identity credentials")
    identity = Identity.objects.filter(pk=identity_id, retired_at=None).first()
    if identity is None or not hmac.compare_digest(bytes(identity.token_hash), hashlib.sha256(token.encode()).digest()):
        raise AuthenticationFailed("Invalid identity credentials")
    now = timezone.now()
    Identity.objects.filter(pk=identity.id, last_seen_at__lt=now - timedelta(hours=1)).update(last_seen_at=now)
    return identity


class IdentityAuthentication(BaseAuthentication):
    def authenticate(self, request):
        return authenticate_identity(request.headers.get("Authorization", ""), request.headers.get("X-Jay-Identity-ID", "")), None

    def authenticate_header(self, request):
        return "Bearer"


class IdentityThrottle(SimpleRateThrottle):
    scope = "read"

    def allow_request(self, request, view):
        self.scope = getattr(view, "throttle_scope", "read" if request.method in {"GET", "HEAD", "OPTIONS"} else "write")
        self.rate = self.get_rate()
        self.num_requests, self.duration = self.parse_rate(self.rate)
        return super().allow_request(request, view)

    def get_cache_key(self, request, view):
        identity = request.user.pk if request.user else request.META.get("REMOTE_ADDR", "")
        return self.cache_format % {"scope": self.scope, "ident": identity}
