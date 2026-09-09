from datetime import timedelta
from uuid import uuid4

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from jay_server.social.models import Group, GroupMembership


@pytest.fixture(autouse=True)
def isolated_throttle_cache():
    from django.core.cache import cache
    cache.clear()


@pytest.fixture
def client():
    api = APIClient()
    identity_id = "1" * 64
    response = api.post("/v1/identities/register", {"id": identity_id, "name": "Alice", "token": "secret" * 10, "time_zone": "UTC"}, format="json")
    assert response.status_code == 201, response.data
    api.credentials(HTTP_AUTHORIZATION="Bearer " + "secret" * 10, HTTP_X_JAY_IDENTITY_ID=identity_id)
    return api


@pytest.fixture
def group(client):
    response = client.post("/v1/groups", {
        "id": str(uuid4()), "membership_id": str(uuid4()), "name": "Household", "alarm_permission": "everyone",
        "notify_alarm_changes": True, "notify_snoozed": True, "notify_dismissed": True,
        "notify_ignored": True, "alarm_time_basis": "member_local", "alarm_time_zone": "UTC",
        "shared_answers": False, "saved_at": (timezone.now() - timedelta(minutes=1)).isoformat(),
    }, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    return Group.objects.get(pk=response.data["id"])


@pytest.fixture
def timer_payload(group):
    return {
        "id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="1" * 64).pk),
        "saved_at": (timezone.now() - timedelta(seconds=30)).isoformat(), "label": "Tea",
        "duration_seconds": 300, "increment_seconds": 60, "expires_at": (timezone.now() + timedelta(minutes=5)).isoformat(),
        "vibrate": True, "vibration_pattern": [0, 100], "vibration_pattern_name": "Default",
        "sound": {"mode": "member_default", "sound_id": None},
    }


@pytest.fixture
def member(client, group):
    from rest_framework.test import APIClient

    other = APIClient()
    identity_id, secret = "2" * 64, "another-secret" * 5
    assert other.post("/v1/identities/register", {"id": identity_id, "name": "Bob", "token": secret, "time_zone": "Asia/Kuala_Lumpur"}, format="json").status_code == 201
    other.credentials(HTTP_AUTHORIZATION="Bearer " + secret, HTTP_X_JAY_IDENTITY_ID=identity_id)
    invite = client.post(f"/v1/groups/{group.pk}/invites", {"id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="1" * 64).pk)}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert invite.status_code == 201, invite.data
    joined = other.post("/v1/groups/join", {"token": invite.data["token"]}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert joined.status_code == 200, joined.data
    return other


@pytest.fixture
def alarm_payload(group):
    return {
        "id": str(uuid4()), "group_id": str(group.pk),
        "membership_id": str(GroupMembership.objects.get(group=group, identity_id="1" * 64).pk),
        "saved_at": (timezone.now() - timedelta(seconds=30)).isoformat(),
        "local_time_ms": 8 * 3600000, "label": "Morning", "enabled": True,
        "days": list(range(7)), "vibrate": True, "start_date": timezone.now().date().isoformat(),
        "repeat_interval": 1, "repeat_unit": "DAY", "repeat_anchor": "DAY_OF_MONTH",
        "repeat_duration": None, "repeat_duration_unit": "DAY", "end_date": None,
        "end_occurrences": None, "advanced": False, "snooze_enabled": True, "snooze_minutes": 5,
        "vibration_pattern": [0, 100], "vibration_pattern_name": "Default",
        "sound": {"mode": "member_default", "sound_id": None},
    }
