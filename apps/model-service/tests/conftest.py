import pytest
from fastapi.testclient import TestClient

from model_service.app import create_app
from model_service.schemas import ModelInfo
from model_service.settings import Settings

SETTINGS = Settings(embedding_model="fake", model_cache_dir="/nonexistent", max_texts_per_request=3, max_chars_per_text=20)


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


@pytest.fixture
def client(embedder: FakeEmbedder) -> TestClient:
    return TestClient(create_app(embedder, SETTINGS))
