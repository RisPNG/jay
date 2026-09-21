import pytest
from rest_framework.test import APIClient

from django.conf import settings


def test_android_app_links_publish_configured_signing_certificates(monkeypatch) -> None:
    certificates = {
        "com.rispng.jay": ["AA:" * 31 + "AA", "BB:" * 31 + "BB"],
        "com.rispng.jay.lite": ["DD:" * 31 + "DD"],
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
        if fingerprints and package in ("com.rispng.jay", "com.rispng.jay.lite")
    ]


def test_unconfigured_app_links_claim_no_apps(monkeypatch) -> None:
    monkeypatch.setattr(settings, "ANDROID_APP_LINKS", {})
    assert APIClient().get("/.well-known/assetlinks.json").json() == []


@pytest.mark.parametrize("path", ["/join?token=example", "/profile?key=example"])
def test_browser_links_offer_both_editions_without_disclosing_link_credentials(path) -> None:
    response = APIClient().get(path, follow=False)
    assert response.status_code == 200
    assert "location" not in response.headers
    assert b'id="open-full"' in response.content
    assert b'id="open-lite"' in response.content
    assert b"example" not in response.content
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["referrer-policy"] == "no-referrer"
    assert "script-src 'self'" in response.headers["content-security-policy"]


def test_browser_link_script_preserves_fragment_only_in_the_app_intent() -> None:
    response = APIClient().get("/app-link.js")
    assert response.status_code == 200
    script = response.content.decode()
    assert "window.location.href" in script
    assert "S.jay_link=${encodeURIComponent(link)}" in script
    assert "S.browser_fallback_url=${encodeURIComponent(store)}" in script
    assert "com.rispng.jay.debug" not in script


def test_documentation_and_schema_are_public():
    client = APIClient()
    assert client.get("/docs").status_code == 200
    assert client.get("/docs/init.js").status_code == 200
    response = client.get("/openapi.json", HTTP_ACCEPT="application/json")
    assert response.status_code == 200
    assert response.json()["openapi"] == "3.0.3"
