import pytest
from fastapi.testclient import TestClient

from jay_server.config import settings
from jay_server.main import app


def test_android_app_links_publish_configured_signing_certificates(monkeypatch) -> None:
    certificates = {
        "com.rispng.jay": ["AA:" * 31 + "AA", "BB:" * 31 + "BB"],
        "com.rispng.jay.debug": ["CC:" * 31 + "CC"],
        "unconfigured": [],
    }
    monkeypatch.setattr(settings, "android_app_links", certificates)
    response = TestClient(app).get("/.well-known/assetlinks.json")
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
    monkeypatch.setattr(settings, "android_app_links", {})
    assert TestClient(app).get("/.well-known/assetlinks.json").json() == []


@pytest.mark.parametrize("path", ["/join?token=example", "/profile?key=example"])
def test_browser_links_redirect_without_credentials_or_fragment_inheritance(path) -> None:
    response = TestClient(app).get(path, follow_redirects=False)
    assert response.status_code == 302
    assert response.headers["location"] == (
        "https://play.google.com/store/apps/details?id=com.rispng.jay#"
    )
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["referrer-policy"] == "no-referrer"
