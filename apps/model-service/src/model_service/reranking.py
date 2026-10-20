"""Reranking backends. The service loads exactly one cross-encoder per process."""

from typing import Protocol

from model_service.embedding import pinned_snapshot
from model_service.schemas import ModelInfo
from model_service.tokens import HuggingFaceTokenCounter, TokenCounter


class Reranker(Protocol):
    @property
    def info(self) -> ModelInfo: ...

    @property
    def tokens(self) -> TokenCounter: ...

    def score(self, query: str, passages: list[str]) -> list[float]: ...


class FastEmbedReranker:
    """An ONNX cross-encoder through fastembed; CPU only, weights loaded from the pinned revision in a local cache directory. A cross-encoder
    reads the query and a passage together, so its cost grows with the number of passages; callers send only the candidates they intend to
    rerank."""

    def __init__(self, model_name: str, revision: str, cache_dir: str) -> None:
        from fastembed.rerank.cross_encoder import TextCrossEncoder

        description = next(m for m in TextCrossEncoder.list_supported_models() if m["model"] == model_name)
        path = pinned_snapshot(cache_dir, description["sources"]["hf"], revision)
        self._model = TextCrossEncoder(model_name=model_name, cache_dir=cache_dir, specific_model_path=str(path))
        self._tokens = HuggingFaceTokenCounter(path)
        self._info = ModelInfo(
            name=model_name,
            task="rerank",
            revision=revision,
            license=description.get("license", "unknown"),
        )

    @property
    def info(self) -> ModelInfo:
        return self._info

    @property
    def tokens(self) -> TokenCounter:
        return self._tokens

    def score(self, query: str, passages: list[str]) -> list[float]:
        return [float(score) for score in self._model.rerank(query, passages)]
