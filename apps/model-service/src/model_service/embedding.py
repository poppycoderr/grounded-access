"""Embedding backends. The service loads exactly one embedding model per process."""

from pathlib import Path
from typing import Literal, Protocol

from model_service.schemas import ModelInfo
from model_service.tokens import HuggingFaceTokenCounter, TokenCounter

InputType = Literal["query", "passage"]

# Query instructions recommended by the model authors; passages are encoded without a prefix.
QUERY_PREFIXES = {
    "BAAI/bge-small-en-v1.5": "Represent this sentence for searching relevant passages: ",
}


class Embedder(Protocol):
    @property
    def info(self) -> ModelInfo: ...

    @property
    def tokens(self) -> TokenCounter: ...

    def embed(self, texts: list[str], input_type: InputType) -> list[list[float]]: ...


class FastEmbedEmbedder:
    """ONNX Runtime embeddings through fastembed; CPU only, weights loaded from the pinned revision in a local cache directory."""

    def __init__(self, model_name: str, revision: str, cache_dir: str) -> None:
        from fastembed import TextEmbedding

        description = next(m for m in TextEmbedding.list_supported_models() if m["model"] == model_name)
        path = pinned_snapshot(cache_dir, description["sources"]["hf"], revision)
        self._model = TextEmbedding(model_name=model_name, cache_dir=cache_dir, specific_model_path=str(path))
        self._tokens = HuggingFaceTokenCounter(path)
        self._prefix = QUERY_PREFIXES.get(model_name, "")
        self._info = ModelInfo(
            name=model_name,
            task="embedding",
            revision=revision,
            dimensions=description["dim"],
            query_prefix=self._prefix,
            passage_prefix="",
            license=description.get("license", "unknown"),
        )

    @property
    def info(self) -> ModelInfo:
        return self._info

    @property
    def tokens(self) -> TokenCounter:
        return self._tokens

    def embed(self, texts: list[str], input_type: InputType) -> list[list[float]]:
        inputs = [self._prefix + t for t in texts] if input_type == "query" else texts
        return [vector.tolist() for vector in self._model.embed(inputs)]


def pinned_snapshot(cache_dir: str, hf_repo: str, revision: str) -> Path:
    """The weights of exactly this revision, in the Hugging Face cache layout models--<org>--<name>/snapshots/<commit sha>/. The service
    loads from this directory only, so it cannot run on weights other than the pinned ones."""
    path = Path(cache_dir) / f"models--{hf_repo.replace('/', '--')}" / "snapshots" / revision
    if not path.is_dir():
        raise RuntimeError(f"{hf_repo} at revision {revision} is not in {cache_dir}; run python -m model_service.download")
    return path
