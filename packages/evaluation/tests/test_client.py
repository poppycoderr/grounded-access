import json

import httpx
import pytest

from ga_eval.client import ApiClient, IngestionFailedError

LOCATION = "/api/v1/ingestion-jobs/7d3f0c1e-0000-0000-0000-000000000001"


def _job(status: str, **fields: object) -> dict:
    return {"jobId": LOCATION.rsplit("/", 1)[1], "status": status, "attempts": 1, "errorCode": None} | fields


def _client(polls: list[dict]) -> tuple[ApiClient, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.method == "POST":
            return httpx.Response(202, json=_job("queued"), headers={"Location": LOCATION})
        return httpx.Response(200, json=polls.pop(0))

    return ApiClient("http://control-plane", transport=httpx.MockTransport(handle), poll_interval=0), seen


def test_ingest_polls_the_job_until_it_succeeds() -> None:
    client, seen = _client([_job("running"), _job("succeeded", created=2)])

    job = client.ingest("token", [{"key": "a", "title": "A", "content": "# A"}])

    assert job["created"] == 2
    assert [r.method for r in seen] == ["POST", "GET", "GET"]
    assert all(r.url.path == LOCATION for r in seen[1:])
    assert all(r.headers["Authorization"] == "Bearer token" for r in seen)


def test_ingest_raises_with_the_error_code_when_the_job_fails() -> None:
    client, _ = _client([_job("failed", attempts=5, errorCode="MODEL_SERVICE_UNAVAILABLE")])

    with pytest.raises(IngestionFailedError, match="MODEL_SERVICE_UNAVAILABLE"):
        client.ingest("token", [])


def test_ingest_gives_up_after_the_timeout() -> None:
    client, _ = _client([_job("running")] * 3)

    with pytest.raises(TimeoutError, match="still"):
        client.ingest("token", [], timeout_seconds=0)


def test_search_sends_the_scope_only_when_a_case_sets_one() -> None:
    seen: list[dict] = []

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"results": []})

    client = ApiClient("http://control-plane", transport=httpx.MockTransport(handle))
    client.search("token", "q", "dense-only", 10)
    client.search("token", "q", "dense-only", 10, "2025-06-01T00:00:00Z", "US")

    assert seen[0] == {"query": "q", "strategy": "dense-only", "k": 10}
    assert seen[1] == {"query": "q", "strategy": "dense-only", "k": 10, "asOf": "2025-06-01T00:00:00Z", "region": "US"}
