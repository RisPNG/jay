import os
import time

import requests


def deploy_release():
    commit = os.environ["RELEASE_COMMIT"]
    services = [os.environ["RENDER_WEB_SERVICE_ID"], os.environ["RENDER_WORKER_SERVICE_ID"]]
    public_url = os.environ["PUBLIC_URL"].rstrip("/")
    if len(commit) != 40 or any(character not in "0123456789abcdef" for character in commit):
        raise ValueError("RELEASE_COMMIT must be the full verified commit SHA")
    if not all(services) or services[0] == services[1] or not public_url.startswith("https://"):
        raise ValueError("Configure both distinct Render services and the HTTPS public URL")
    with requests.Session() as session:
        session.headers["Authorization"] = "Bearer " + os.environ["RENDER_API_KEY"]
        for index, service in enumerate(services):
            endpoint = f"https://api.render.com/v1/services/{service}/deploys"
            response = session.post(endpoint, json={"commitId": commit}, timeout=(5, 20))
            response.raise_for_status()
            deployment_id = response.json()["id"]
            deadline = time.monotonic() + 1200
            while True:
                response = session.get(f"{endpoint}/{deployment_id}", timeout=(5, 20))
                response.raise_for_status()
                deployment = response.json()
                status = deployment["status"]
                if status == "live":
                    if deployment["commit"]["id"] != commit:
                        raise RuntimeError("Render deployed a different commit")
                    break
                if status in {"build_failed", "update_failed", "pre_deploy_failed", "canceled", "deactivated"}:
                    raise RuntimeError(f"Render deployment stopped with status {status}")
                if time.monotonic() >= deadline:
                    raise TimeoutError("Render deployment exceeded twenty minutes; inspect its existing deploy before retrying")
                time.sleep(5)
            if index == 0:
                response = requests.get(f"{public_url}/health/ready", timeout=(5, 20))
                response.raise_for_status()
                if response.json().get("status") != "ready":
                    raise RuntimeError("The deployed web service is not ready")
            print(f"Deployed {service} at {commit}", flush=True)


if __name__ == "__main__":
    deploy_release()
