"""Reranking backends. The service loads exactly one cross-encoder per process."""

from typing import Protocol

from model_service.embedding import snapshot_revision
from model_service.schemas import ModelInfo


class Reranker(Protocol):
    @property
    def info(self) -> ModelInfo: ...

    def score(self, query: str, passages: list[str]) -> list[float]: ...


class FastEmbedReranker:
    """An ONNX cross-encoder through fastembed; CPU only, weights resolved from a local cache directory. A cross-encoder reads the query and a
    passage together, so its cost grows with the number of passages; callers send only the candidates they intend to rerank."""

    def __init__(self, model_name: str, cache_dir: str) -> None:
        from fastembed.rerank.cross_encoder import TextCrossEncoder

        self._model = TextCrossEncoder(model_name=model_name, cache_dir=cache_dir)
        description = next(m for m in TextCrossEncoder.list_supported_models() if m["model"] == model_name)
        self._info = ModelInfo(
            name=model_name,
            task="rerank",
            revision=snapshot_revision(cache_dir, description["sources"]["hf"]),
            license=description.get("license", "unknown"),
        )

    @property
    def info(self) -> ModelInfo:
        return self._info

    def score(self, query: str, passages: list[str]) -> list[float]:
        return [float(score) for score in self._model.rerank(query, passages)]
