from unittest.mock import MagicMock

import pytest

from jay_server import deployment as deploy


@pytest.mark.parametrize("status", ["live", "pre_deploy_failed"])
def test_release_deploys_worker_only_after_matching_web_is_ready(monkeypatch, status):
    commit = "a" * 40
    for key, value in {"RELEASE_COMMIT": commit, "RENDER_WEB_SERVICE_ID": "web", "RENDER_WORKER_SERVICE_ID": "worker", "RENDER_API_KEY": "test-only", "PUBLIC_URL": "https://jay.example"}.items():
        monkeypatch.setenv(key, value)
    session = MagicMock()
    session.__enter__.return_value = session
    session.post.return_value.json.return_value = {"id": "deployment"}
    session.get.return_value.json.return_value = {"status": status, "commit": {"id": commit}}
    monkeypatch.setattr(deploy.requests, "Session", MagicMock(return_value=session))
    health = MagicMock()
    health.return_value.json.return_value = {"status": "ready"}
    monkeypatch.setattr(deploy.requests, "get", health)
    if status == "live":
        deploy.deploy_release()
        assert session.post.call_count == 2
        assert session.post.call_args_list[0].args[0].endswith("/web/deploys")
        assert session.post.call_args_list[1].args[0].endswith("/worker/deploys")
        assert all(call.kwargs["json"] == {"commitId": commit} for call in session.post.call_args_list)
        health.assert_called_once_with("https://jay.example/health/ready", timeout=(5, 20))
    else:
        with pytest.raises(RuntimeError, match="pre_deploy_failed"):
            deploy.deploy_release()
        assert session.post.call_count == 1
        health.assert_not_called()
