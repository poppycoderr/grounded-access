"""Admission by lane (ADR-0010): full lanes refuse at once, late requests are not computed, and lanes do not share slots."""

import asyncio
import threading
import time

import pytest
from fastapi.testclient import TestClient

from model_service.admission import Lane, Refused
from model_service.app import create_app
from model_service.settings import Settings


def run(coroutine):
    return asyncio.run(coroutine)


def test_a_lane_admits_up_to_its_slots_and_then_lets_requests_wait():
    async def scenario() -> float:
        lane = Lane("query", slots=1, queue=1, default_wait_seconds=1)

        async def first() -> None:
            async with lane.admit(None, time.monotonic()):
                await asyncio.sleep(0.1)

        async def second() -> float:
            await asyncio.sleep(0.01)
            async with lane.admit(None, time.monotonic()) as admitted:
                return admitted.queue_ms

        _, waited = await asyncio.gather(first(), second())
        return waited

    assert run(scenario()) >= 80


def test_a_full_lane_refuses_at_once():
    async def scenario() -> tuple[str, float]:
        lane = Lane("rerank", slots=1, queue=0, default_wait_seconds=5)
        async with lane.admit(None, time.monotonic()):
            started = time.monotonic()
            with pytest.raises(Refused) as refused:
                async with lane.admit(None, time.monotonic()):
                    pass
            return refused.value.code, time.monotonic() - started

    code, elapsed = run(scenario())
    assert code == "BUSY"
    assert elapsed < 0.1


def test_a_request_whose_deadline_passes_while_it_waits_is_refused_and_not_run():
    async def scenario() -> tuple[str, float, list]:
        lane = Lane("query", slots=1, queue=4, default_wait_seconds=5)
        ran: list = []
        async with lane.admit(None, time.monotonic()):
            started = time.monotonic()
            with pytest.raises(Refused) as late:
                async with lane.admit(100, time.monotonic()):
                    ran.append(True)
            elapsed = time.monotonic() - started
        with pytest.raises(Refused, match="before the query request was admitted"):
            async with lane.admit(0, time.monotonic()):
                pass
        with pytest.raises(Refused, match="before the query request was admitted"):
            async with lane.admit(100, time.monotonic() - 0.2):
                pass
        return late.value.code, elapsed, ran

    code, elapsed, ran = run(scenario())
    assert code == "DEADLINE_EXCEEDED"
    assert 0.09 <= elapsed < 0.5
    assert ran == []


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
    client = TestClient(create_app(SlowEmbedder(embedder), reranker, settings)).__enter__()
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


# Review 2026-10-21, D4 and N3: everything before the model runs counts against the caller's deadline, tokenizing is admitted like inference,
# the health check does not depend on the worker threads, and a caller cannot ask the service to wait longer than its lane allows.


class SlowCount:
    """Counts like the word fake, but takes a while, as a real tokenizer does on a large batch."""

    def __init__(self, tokens, seconds: float) -> None:
        self._tokens = tokens
        self._seconds = seconds
        self.max_tokens = tokens.max_tokens

    def count(self, text: str, pair: str | None = None) -> int:
        time.sleep(self._seconds)
        return self._tokens.count(text, pair)

    def fitting_chars(self, text: str, budget: int) -> int:
        time.sleep(self._seconds)
        return self._tokens.fitting_chars(text, budget)


class SlowTokens:
    def __init__(self, fake, seconds: float) -> None:
        self._fake = fake
        self.info = fake.info
        self.tokens = SlowCount(fake.tokens, seconds)
        self.calls = fake.calls

    def embed(self, texts, input_type):
        return self._fake.embed(texts, input_type)

    def score(self, query, passages):
        return self._fake.score(query, passages)


def test_counting_that_uses_up_the_deadline_stops_the_request_before_the_model_runs(embedder, reranker):
    client = TestClient(create_app(SlowTokens(embedder, 0.15), reranker, _settings()))

    response = client.post("/v1/embed", json={"input_type": "query", "texts": ["a"]}, headers={"X-Deadline-Ms": "50"})

    assert response.status_code == 503
    assert response.json()["code"] == "DEADLINE_EXCEEDED"
    assert embedder.calls == []


def test_tokenizing_is_admitted_and_cannot_starve_the_health_check(embedder, reranker):
    slow = SlowTokens(reranker, 0.3)
    client = TestClient(create_app(SlowTokens(embedder, 0.3), slow, _settings())).__enter__()
    statuses: list[int] = []

    def tokenize() -> None:
        statuses.append(client.post("/v1/tokenize", json={"texts": ["a b"], "budget": 5}).status_code)

    flood = [threading.Thread(target=tokenize) for _ in range(48)]
    for thread in flood:
        thread.start()
    time.sleep(0.1)
    started = time.monotonic()
    health = client.get("/healthz")
    elapsed = time.monotonic() - started
    for thread in flood:
        thread.join()

    assert health.status_code == 200
    assert elapsed < 0.2, f"the health check waited {elapsed:.2f} s for worker threads"
    assert 503 in statuses, "beyond its slots and queue, tokenizing is refused instead of queueing without bound"


def test_a_caller_cannot_make_the_service_wait_longer_than_the_lane_allows(embedder, reranker):
    settings = Settings(**{**vars(_settings()), "query_slots": 1, "query_wait_seconds": 0.2})
    client = TestClient(create_app(SlowEmbedder(SlowQueryFake(embedder)), reranker, settings)).__enter__()
    holder = threading.Thread(target=lambda: client.post("/v1/embed", json={"input_type": "query", "texts": ["a"]}))
    holder.start()
    time.sleep(0.05)

    started = time.monotonic()
    response = client.post("/v1/embed", json={"input_type": "query", "texts": ["b"]}, headers={"X-Deadline-Ms": "100000"})
    elapsed = time.monotonic() - started
    holder.join()

    assert response.status_code == 503
    assert response.json()["code"] == "DEADLINE_EXCEEDED"
    assert 0.15 <= elapsed < 0.6


class SlowQueryFake:
    """Query embeddings take one second."""

    def __init__(self, fake) -> None:
        self._fake = fake
        self.info = fake.info
        self.tokens = fake.tokens

    def embed(self, texts, input_type):
        time.sleep(1.0)
        return self._fake.embed(texts, input_type)
