from pathlib import Path

from django.conf import settings
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.http import Http404, HttpResponse
from django.shortcuts import render
from django.views import View
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView


class AppLinksView(APIView):
    authentication_classes = []
    permission_classes = []

    @extend_schema(responses={200: {"type": "array", "items": {"type": "object"}}})
    def get(self, request):
        return Response([{"relation": ["delegate_permission/common.handle_all_urls"], "target": {"namespace": "android_app", "package_name": package, "sha256_cert_fingerprints": hashes}} for package, hashes in settings.ANDROID_APP_LINKS.items() if hashes])


class InstallView(View):
    def get(self, request):
        response = HttpResponse(status=302)
        response["Location"] = "https://play.google.com/store/apps/details?id=com.rispng.jay#"
        response["Cache-Control"] = "no-store"
        response["Referrer-Policy"] = "no-referrer"
        return response


class HealthLiveView(APIView):
    authentication_classes = []
    permission_classes = []
    throttle_classes = []

    @extend_schema(responses={200: {"type": "object", "properties": {"status": {"type": "string"}}}})
    def get(self, request):
        return Response({"status": "ok"})


class HealthReadyView(APIView):
    authentication_classes = []
    permission_classes = []
    throttle_classes = []

    @extend_schema(responses={200: {"type": "object"}, 503: {"type": "object"}})
    def get(self, request):
        from django.db import DatabaseError
        try:
            executor = MigrationExecutor(connection)
            ready = not executor.migration_plan(executor.loader.graph.leaf_nodes())
        except DatabaseError:
            ready = False
        return Response({"status": "ready" if ready else "unavailable"}, status=200 if ready else 503)


def documentation(request):
    if not settings.API_DOCS_ENABLED:
        raise Http404
    response = render(request, "api.html", {"configuration": {"url": "/openapi.json", "hideClientButton": True}})
    response["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"
    return response


def documentation_asset(request, asset_name="scalar-1.68.0.js"):
    if not settings.API_DOCS_ENABLED:
        raise Http404
    response = HttpResponse((Path(__file__).parent / "static" / asset_name).read_bytes(), content_type="text/javascript")
    response["Cache-Control"] = "no-cache" if asset_name == "init.js" else "public, max-age=31536000, immutable"
    return response
