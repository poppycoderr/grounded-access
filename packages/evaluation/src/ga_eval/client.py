"""Thin client for the control-plane API."""

import time

import httpx


class ApiClient:
    def __init__(self, base_url: str, timeout: float = 60.0) -> None:
        self._http = httpx.Client(base_url=base_url, timeout=timeout)

    def wait_until_ready(self, timeout_seconds: float = 120.0) -> None:
        deadline = time.monotonic() + timeout_seconds
        while True:
            try:
                if self._http.get("/actuator/health").json().get("status") == "UP":
                    return
            except (httpx.HTTPError, ValueError):
                pass
            if time.monotonic() > deadline:
                raise TimeoutError(f"control plane at {self._http.base_url} not ready after {timeout_seconds:.0f}s")
            time.sleep(2)

    def ingest(self, token: str, documents: list[dict]) -> dict:
        response = self._http.post("/api/v1/ingestion-jobs", json={"documents": documents}, headers=_auth(token))
        response.raise_for_status()
        return response.json()

    def search(self, token: str, query: str, strategy: str, k: int) -> dict:
        response = self._http.post("/api/v1/retrieval/search", json={"query": query, "strategy": strategy, "k": k}, headers=_auth(token))
        response.raise_for_status()
        return response.json()


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
