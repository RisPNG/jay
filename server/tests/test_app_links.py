import pytest
from rest_framework.test import APIClient

from django.conf import settings


def test_android_app_links_publish_configured_signing_certificates(monkeypatch) -> None:
    certificates = {
        "com.rispng.jay": ["AA:" * 31 + "AA", "BB:" * 31 + "BB"],
        "com.rispng.jay.debug": ["CC:" * 31 + "CC"],
        "unconfigured": [],
    }
    monkeypatch.setattr(settings, "ANDROID_APP_LINKS", certificates)
    response = APIClient().get("/.well-known/assetlinks.json")
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.json() == [
        {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": package,
                "sha256_cert_fingerprints": fingerprints,
            },
        }
        for package, fingerprints in certificates.items()
        if fingerprints
    ]


def test_unconfigured_app_links_claim_no_apps(monkeypatch) -> None:
    monkeypatch.setattr(settings, "ANDROID_APP_LINKS", {})
    assert APIClient().get("/.well-known/assetlinks.json").json() == []


@pytest.mark.parametrize("path", ["/join?token=example", "/profile?key=example"])
def test_browser_links_redirect_without_credentials_or_fragment_inheritance(path) -> None:
    response = APIClient().get(path, follow=False)
    assert response.status_code == 302
    assert response.headers["location"] == (
        "https://play.google.com/store/apps/details?id=com.rispng.jay#"
    )
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["referrer-policy"] == "no-referrer"


def test_documentation_and_schema_are_public():
    client = APIClient()
    assert client.get("/docs").status_code == 200
    assert client.get("/docs/init.js").status_code == 200
    response = client.get("/openapi.json", HTTP_ACCEPT="application/json")
    assert response.status_code == 200
    assert response.json()["openapi"] == "3.0.3"
