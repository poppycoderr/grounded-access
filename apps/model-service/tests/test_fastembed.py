"""Runs the real embedding model; needs the weights in the cache, so it only runs when GA_RUN_MODEL_TESTS=1."""

import math
import os

import pytest

from model_service.embedding import FastEmbedEmbedder
from model_service.settings import Settings

pytestmark = pytest.mark.skipif(os.getenv("GA_RUN_MODEL_TESTS") != "1", reason="set GA_RUN_MODEL_TESTS=1 to run the real model")


@pytest.fixture(scope="module")
def embedder() -> FastEmbedEmbedder:
    settings = Settings.from_env()
    return FastEmbedEmbedder(settings.embedding_model, settings.embedding_revision, settings.model_cache_dir)


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=True)) / (math.hypot(*a) * math.hypot(*b))


def test_reports_dimensions_and_the_pinned_revision(embedder):
    assert embedder.info.dimensions == 384
    assert embedder.info.revision == Settings.from_env().embedding_revision


def test_vectors_are_normalized_and_deterministic(embedder):
    first = embedder.embed(["EU employees receive two paid volunteer days."], "passage")[0]
    second = embedder.embed(["EU employees receive two paid volunteer days."], "passage")[0]

    assert len(first) == 384
    assert math.isclose(math.hypot(*first), 1.0, rel_tol=1e-3)
    assert first == second


def test_query_ranks_the_relevant_passage_first(embedder):
    query = embedder.embed(["How many paid volunteer days do EU employees get?"], "query")[0]
    relevant, unrelated = embedder.embed(
        ["EU employees receive two paid volunteer days per calendar year.", "Rotate the billing database credentials every 90 days."], "passage"
    )

    assert cosine(query, relevant) > cosine(query, unrelated)


@pytest.fixture(scope="module")
def reranker():
    from model_service.reranking import FastEmbedReranker

    settings = Settings.from_env()
    return FastEmbedReranker(settings.reranker_model, settings.reranker_revision, settings.model_cache_dir)


def test_the_reranker_reports_the_pinned_revision(reranker):
    assert reranker.info.task == "rerank"
    assert reranker.info.revision == Settings.from_env().reranker_revision


def test_the_reranker_prefers_the_passage_that_answers_the_question(reranker):
    scores = reranker.score(
        "How many paid volunteer days do EU employees receive?",
        [
            "Economy class is the default for flights shorter than six hours.",
            "Employees based in the EU receive two paid volunteer days per calendar year.",
        ],
    )

    assert scores[1] > scores[0]
    assert scores == reranker.score(
        "How many paid volunteer days do EU employees receive?",
        [
            "Economy class is the default for flights shorter than six hours.",
            "Employees based in the EU receive two paid volunteer days per calendar year.",
        ],
    )


def test_both_models_accept_512_tokens_and_a_fitting_prefix_fits(embedder, reranker):
    long_text = " ".join(f"word{i} unbelievable" for i in range(400))

    for counter in (embedder.tokens, reranker.tokens):
        assert counter.max_tokens == 512
        assert counter.count(long_text) > 512
        prefix = long_text[: counter.fitting_chars(long_text, 384)]
        assert 380 <= counter.count(prefix) <= 384
        assert long_text.startswith(prefix)


def test_the_real_service_refuses_an_input_it_would_have_cut(embedder, reranker):
    from fastapi.testclient import TestClient

    from model_service.app import create_app

    client = TestClient(create_app(embedder, reranker, Settings.from_env()))
    long_text = " ".join(f"word{i}" for i in range(600))

    refused = client.post("/v1/embed", json={"input_type": "passage", "texts": [long_text]})
    tokenized = client.post("/v1/tokenize", json={"texts": [long_text], "budget": 384}).json()

    assert refused.status_code == 422
    assert refused.json()["code"] == "TEXT_TOO_LONG"
    assert tokenized["max_tokens"] == 512
    accepted = client.post("/v1/embed", json={"input_type": "passage", "texts": [long_text[: tokenized["fitting_chars"][0]]]})
    assert accepted.status_code == 200
