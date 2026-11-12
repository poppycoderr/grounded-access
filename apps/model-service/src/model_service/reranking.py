"""Reranking backends. The service loads exactly one cross-encoder per process."""

from collections.abc import Sequence
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


# Passages per inference call. fastembed pads every passage of a call to the longest one, so one long passage would make a whole pool of short
# ones as slow as long ones; sorted small batches confine it to its own batch (ADR-0010, amendment of 2026-11-12).
BATCH_SIZE = 4


def length_batches(lengths: Sequence[int], size: int = BATCH_SIZE) -> list[list[int]]:
    """Indexes of the passages grouped into batches of at most `size`, shortest first, so that each batch holds passages of similar length."""
    order = sorted(range(len(lengths)), key=lambda i: (lengths[i], i))
    return [order[k : k + size] for k in range(0, len(order), size)]


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
        scores = [0.0] * len(passages)
        for batch in length_batches([self._tokens.count(query, passage) for passage in passages]):
            batch_scores = self._model.rerank(query, [passages[i] for i in batch], batch_size=len(batch))
            for i, score in zip(batch, batch_scores, strict=True):
                scores[i] = float(score)
        return scores
