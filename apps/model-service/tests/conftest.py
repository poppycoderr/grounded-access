import pytest
from fastapi.testclient import TestClient

from model_service.app import create_app
from model_service.schemas import ModelInfo
from model_service.settings import Settings

SETTINGS = Settings(
    embedding_model="fake",
    reranker_model="fake-reranker",
    model_cache_dir="/nonexistent",
    max_texts_per_request=3,
    max_chars_per_text=20,
    max_passages_per_request=3,
)


class FakeEmbedder:
    info = ModelInfo(name="fake", task="embedding", revision="r1", dimensions=2, query_prefix="q: ", license="none")

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
