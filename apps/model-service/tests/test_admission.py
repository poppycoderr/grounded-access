"""Admission by lane (ADR-0010): full lanes refuse at once, late requests are not computed, and lanes do not share slots."""

import threading
import time

import pytest
from fastapi.testclient import TestClient

from model_service.admission import Lane, Refused
from model_service.app import create_app
from model_service.settings import Settings


def test_a_lane_admits_up_to_its_slots_and_then_lets_requests_wait():
    lane = Lane("query", slots=1, queue=1, default_wait_seconds=1)
    with lane.admit(None) as first:
        assert first.queue_ms < 50
        waited: list[float] = []

        def second() -> None:
            with lane.admit(None) as admitted:
                waited.append(admitted.queue_ms)

        worker = threading.Thread(target=second)
        worker.start()
        time.sleep(0.1)
    worker.join()
    assert waited and waited[0] >= 90


def test_a_full_lane_refuses_at_once():
    lane = Lane("rerank", slots=1, queue=0, default_wait_seconds=5)
    with lane.admit(None):
        started = time.monotonic()
        with pytest.raises(Refused) as refused, lane.admit(None):
            pass
    assert refused.value.code == "BUSY"
    assert time.monotonic() - started < 0.1


def test_a_request_whose_deadline_passes_while_it_waits_is_refused_and_not_run():
    lane = Lane("query", slots=1, queue=4, default_wait_seconds=5)
    ran = []
    with lane.admit(None):
        started = time.monotonic()
        with pytest.raises(Refused) as late, lane.admit(deadline_ms=100):
            ran.append(True)
    assert late.value.code == "DEADLINE_EXCEEDED"
    assert 0.09 <= time.monotonic() - started < 0.5
    assert ran == []
    with pytest.raises(Refused, match="before the query request arrived"), lane.admit(deadline_ms=0):
        pass


class SlowEmbedder:
    """Passage embeddings take a while; query embeddings are instant."""

    def __init__(self, fake) -> None:
        self._fake = fake
        self.info = fake.info
        self.tokens = fake.tokens

    def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
        if input_type == "passage":
            time.sleep(0.5)
        return self._fake.embed(texts, input_type)


def test_a_passage_flood_neither_delays_nor_refuses_queries(embedder, reranker):
    settings = Settings(**{**vars(_settings()), "passage_slots": 1, "passage_queue": 1, "query_slots": 1})
    client = TestClient(create_app(SlowEmbedder(embedder), reranker, settings))
    statuses: list[int] = []

    def passage() -> None:
        statuses.append(client.post("/v1/embed", json={"input_type": "passage", "texts": ["a"]}).status_code)

    flood = [threading.Thread(target=passage) for _ in range(4)]
    for thread in flood:
        thread.start()
    time.sleep(0.1)
    started = time.monotonic()
    query = client.post("/v1/embed", json={"input_type": "query", "texts": ["a"]})
    elapsed = time.monotonic() - started
    for thread in flood:
        thread.join()

    assert query.status_code == 200
    assert elapsed < 0.3
    assert int(query.headers["X-Queue-Ms"]) < 100 and "X-Compute-Ms" in query.headers
    assert sorted(statuses) == [200, 200, 503, 503], "one running, one waiting, the rest refused"


def test_the_deadline_header_reaches_the_lane(client):
    response = client.post("/v1/embed", json={"input_type": "query", "texts": ["a"]}, headers={"X-Deadline-Ms": "0"})

    assert response.status_code == 503
    assert response.json()["code"] == "DEADLINE_EXCEEDED"
    assert response.headers["Retry-After"] == "1"


def _settings() -> Settings:
    from conftest import SETTINGS

    return SETTINGS
