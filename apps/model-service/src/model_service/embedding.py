"""Embedding backends. The service loads exactly one embedding model per process."""

from pathlib import Path
from typing import Literal, Protocol

from model_service.schemas import ModelInfo

InputType = Literal["query", "passage"]

# Query instructions recommended by the model authors; passages are encoded without a prefix.
QUERY_PREFIXES = {
    "BAAI/bge-small-en-v1.5": "Represent this sentence for searching relevant passages: ",
}


class Embedder(Protocol):
    @property
    def info(self) -> ModelInfo: ...

    def embed(self, texts: list[str], input_type: InputType) -> list[list[float]]: ...


class FastEmbedEmbedder:
    """ONNX Runtime embeddings through fastembed; CPU only, weights resolved from a local cache directory."""

    def __init__(self, model_name: str, cache_dir: str) -> None:
        from fastembed import TextEmbedding

        self._model = TextEmbedding(model_name=model_name, cache_dir=cache_dir)
        description = next(m for m in TextEmbedding.list_supported_models() if m["model"] == model_name)
        self._prefix = QUERY_PREFIXES.get(model_name, "")
        self._info = ModelInfo(
            name=model_name,
            task="embedding",
            revision=_snapshot_revision(cache_dir, description["sources"]["hf"]),
            dimensions=description["dim"],
            query_prefix=self._prefix,
            license=description.get("license", "unknown"),
        )

    @property
    def info(self) -> ModelInfo:
        return self._info

    def embed(self, texts: list[str], input_type: InputType) -> list[list[float]]:
        inputs = [self._prefix + t for t in texts] if input_type == "query" else texts
        return [vector.tolist() for vector in self._model.embed(inputs)]


def _snapshot_revision(cache_dir: str, hf_repo: str) -> str:
    """Hugging Face cache layout: models--<org>--<name>/snapshots/<commit sha>/."""
    snapshots = Path(cache_dir) / f"models--{hf_repo.replace('/', '--')}" / "snapshots"
    revisions = sorted(p.name for p in snapshots.iterdir()) if snapshots.is_dir() else []
    return revisions[-1] if len(revisions) == 1 else "unknown"
