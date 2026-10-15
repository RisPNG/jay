from pathlib import Path
import os

import environ
from dotenv import load_dotenv


BASE_DIR = Path(__file__).resolve().parent.parent
if os.environ.get("LOAD_ENV_FILE", "false").lower() == "true":
    load_dotenv(BASE_DIR / ".env")
env = environ.Env()
SECRET_KEY = env("SECRET_KEY")
DEBUG = env.bool("DEBUG", default=False)
ALLOWED_HOSTS = env.list("ALLOWED_HOSTS", default=["localhost", "127.0.0.1"])
ROOT_URLCONF = "jay_server.urls"
ASGI_APPLICATION = "jay_server.asgi.application"
INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "rest_framework",
    "drf_spectacular",
    "channels",
    "jay_server.social.apps.SocialConfig",
]
MIDDLEWARE = ["django.middleware.security.SecurityMiddleware", "jay_server.social.middleware.RequestLogMiddleware"]
LOGGING = {
    "version": 1, "disable_existing_loggers": False,
    "handlers": {"console": {"class": "logging.StreamHandler"}},
    "loggers": {"jay.requests": {"handlers": ["console"], "level": "INFO", "propagate": False}},
}
USE_TZ = True
TIME_ZONE = "UTC"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
APPEND_SLASH = False
DATABASES = {"default": env.db()}
DATABASES["default"]["CONN_MAX_AGE"] = 0
DATABASES["default"].setdefault("OPTIONS", {}).update({
    "pool": {
        "min_size": 1,
        "max_size": env.int("DATABASE_POOL_SIZE", default=8),
        "timeout": 2,
        "max_waiting": 64,
    },
    "options": "-c lock_timeout=2000 -c statement_timeout=10000",
})
DATA_UPLOAD_MAX_MEMORY_SIZE = 256 * 1024
FILE_UPLOAD_MAX_MEMORY_SIZE = 256 * 1024
SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https") if env.bool("TRUST_PROXY", default=False) else None
SECURE_CONTENT_TYPE_NOSNIFF = True
TEMPLATES = [{
    "BACKEND": "django.template.backends.django.DjangoTemplates",
    "DIRS": [BASE_DIR / "jay_server" / "templates"],
    "APP_DIRS": False,
    "OPTIONS": {},
}]
REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": ["jay_server.social.authentication.IdentityAuthentication"],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.IsAuthenticated"],
    "DEFAULT_PARSER_CLASSES": ["rest_framework.parsers.JSONParser"],
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
    "DEFAULT_SCHEMA_CLASS": "jay_server.social.schema.JaySchema",
    "DEFAULT_THROTTLE_CLASSES": ["jay_server.social.authentication.IdentityThrottle"],
    "DEFAULT_THROTTLE_RATES": {"read": "600/min", "write": "120/min", "upload": "10/min", "registration": "60/min"},
    "EXCEPTION_HANDLER": "jay_server.social.errors.api_exception_handler",
    "UNAUTHENTICATED_USER": None,
}
SPECTACULAR_SETTINGS = {
    "TITLE": "Jay API",
    "DESCRIPTION": "Shared clock synchronization",
    "VERSION": "1.0.0",
    "OAS_VERSION": "3.0.3",
    "SERVE_INCLUDE_SCHEMA": False,
    "COMPONENT_SPLIT_REQUEST": True,
    "ENUM_NAME_OVERRIDES": {
        "RepeatUnit": "jay_server.social.models.RepeatUnit.choices",
        "SoundMode": "jay_server.social.models.SoundMode.choices",
        "ActivityKind": "jay_server.social.models.ActivityKind.choices",
    },
    "APPEND_PATHS": {
        "/v1/events": {"get": {
            "operationId": "v1_events_listen", "tags": ["v1"],
            "description": "Authenticated SSE invalidations. Sync frames contain all and scopes; heartbeats arrive every fifteen seconds. Fetch durable scope pages after each hint and reconnect. Frames are not durable events or acknowledgements.",
            "security": [{"bearerIdentity": [], "identityId": []}],
            "responses": {
                "200": {"description": "Live invalidation stream", "content": {"text/event-stream": {"schema": {"type": "string"}, "example": "event: sync\ndata: {\"all\":true,\"scopes\":[]}\n\n"}}},
                "401": {"description": "Invalid identity credentials"},
                "503": {"description": "Capacity or database unavailable; retry"},
            },
        }},
    },
}
PUBLIC_URL = env("PUBLIC_URL", default="http://127.0.0.1:8000").rstrip("/")
ANDROID_APP_LINKS = env.json("ANDROID_APP_LINKS", default={})
INVITE_LIFETIME_HOURS = env.int("INVITE_LIFETIME_HOURS", default=24)
PLAY_ENTITLEMENT_LIFETIME_HOURS = env.int("PLAY_ENTITLEMENT_LIFETIME_HOURS", default=48)
SHARED_SOUND_ACCESS = env("SHARED_SOUND_ACCESS", default="play")
if SHARED_SOUND_ACCESS not in {"play", "everyone"}:
    from django.core.exceptions import ImproperlyConfigured
    raise ImproperlyConfigured("SHARED_SOUND_ACCESS must be play or everyone")
IDENTITY_INACTIVITY_TIMEOUT_DAYS = env.int("IDENTITY_INACTIVITY_TIMEOUT_DAYS", default=120)
FIREBASE_CREDENTIALS_JSON = env("FIREBASE_CREDENTIALS_JSON", default="")
GOOGLE_PLAY_CREDENTIALS_JSON = env("GOOGLE_PLAY_CREDENTIALS_JSON", default="")
B2_S3_ENDPOINT = env("B2_S3_ENDPOINT", default="")
B2_BUCKET_NAME = env("B2_BUCKET_NAME", default="")
B2_APPLICATION_KEY_ID = env("B2_APPLICATION_KEY_ID", default="")
B2_APPLICATION_KEY = env("B2_APPLICATION_KEY", default="")
API_DOCS_ENABLED = env.bool("API_DOCS_ENABLED", default=True)
SYNC_PAGE_SIZE = 200
SYNC_PAGE_BYTES = 256 * 1024
SYNC_RETENTION_DAYS = 30
SYNC_CURSOR_SECONDS = 15 * 60
MAX_HTTP_REQUESTS = 64
MAX_EVENT_CONNECTIONS = 12000
LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,
    "handlers": {"console": {"class": "logging.StreamHandler"}},
    "root": {"handlers": ["console"], "level": "INFO"},
}
