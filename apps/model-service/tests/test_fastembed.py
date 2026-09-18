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
    return FastEmbedEmbedder(settings.embedding_model, settings.model_cache_dir)


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=True)) / (math.hypot(*a) * math.hypot(*b))


def test_reports_dimensions_and_a_pinned_revision(embedder):
    assert embedder.info.dimensions == 384
    assert embedder.info.revision != "unknown"


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
