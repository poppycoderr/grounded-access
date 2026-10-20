import re

import pytest
from fastapi.testclient import TestClient

from model_service.app import create_app
from model_service.schemas import ModelInfo
from model_service.settings import Settings

SETTINGS = Settings(
    embedding_model="fake",
    embedding_revision="r1",
    reranker_model="fake-reranker",
    reranker_revision="r2",
    model_cache_dir="/nonexistent",
    max_texts_per_request=3,
    max_chars_per_text=20,
    max_passages_per_request=3,
)


class WordTokens:
    """One token per whitespace-separated word, plus [CLS] and [SEP] (and one more [SEP] for a pair)."""

    def __init__(self, max_tokens: int) -> None:
        self.max_tokens = max_tokens

    def count(self, text: str, pair: str | None = None) -> int:
        return len(text.split()) + 2 + (len(pair.split()) + 1 if pair is not None else 0)

    def fitting_chars(self, text: str, budget: int) -> int:
        words = list(re.finditer(r"\S+", text))
        if len(words) + 2 <= budget:
            return len(text)
        allowed = budget - 2
        return words[allowed - 1].end() if allowed > 0 else 0


class FakeEmbedder:
    info = ModelInfo(name="fake", task="embedding", revision="r1", dimensions=2, query_prefix="q: ", passage_prefix="", license="none")
    tokens = WordTokens(8)

    def __init__(self) -> None:
        self.calls: list[tuple[list[str], str]] = []

    def embed(self, texts: list[str], input_type: str) -> list[list[float]]:
        self.calls.append((texts, input_type))
        return [[float(len(t)), 1.0] for t in texts]


@pytest.fixture
def embedder() -> FakeEmbedder:
    return FakeEmbedder()


class FakeReranker:
    """Scores a passage by how many of the query's words it contains."""

    info = ModelInfo(name="fake-reranker", task="rerank", revision="r2", license="none")
    tokens = WordTokens(10)

    def __init__(self) -> None:
        self.calls: list[tuple[str, list[str]]] = []

    def score(self, query: str, passages: list[str]) -> list[float]:
        self.calls.append((query, passages))
        return [float(sum(word in passage.split() for word in query.split())) for passage in passages]


@pytest.fixture
def reranker() -> FakeReranker:
    return FakeReranker()


@pytest.fixture
def client(embedder: FakeEmbedder, reranker: FakeReranker) -> TestClient:
    return TestClient(create_app(embedder, reranker, SETTINGS))
