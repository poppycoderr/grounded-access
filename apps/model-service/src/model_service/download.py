"""Fetches the pinned revision of each configured model into the cache directory; run at image build time so the service starts offline. Fails
if a revision cannot be fetched, so an image never contains weights other than the pinned ones."""

from fastembed import TextEmbedding
from fastembed.rerank.cross_encoder import TextCrossEncoder
from huggingface_hub import snapshot_download

from model_service.embedding import FastEmbedEmbedder
from model_service.reranking import FastEmbedReranker
from model_service.settings import Settings

# The files fastembed reads besides the model file itself.
SUPPORT_FILES = ["config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "preprocessor_config.json"]


def fetch(descriptions: list[dict], model_name: str, revision: str, cache_dir: str) -> None:
    description = next(m for m in descriptions if m["model"] == model_name)
    patterns = SUPPORT_FILES + [description["model_file"], *description.get("additional_files", [])]
    snapshot_download(repo_id=description["sources"]["hf"], revision=revision, allow_patterns=patterns, cache_dir=cache_dir)


if __name__ == "__main__":
    settings = Settings.from_env()
    fetch(TextEmbedding.list_supported_models(), settings.embedding_model, settings.embedding_revision, settings.model_cache_dir)
    fetch(TextCrossEncoder.list_supported_models(), settings.reranker_model, settings.reranker_revision, settings.model_cache_dir)
    info = FastEmbedEmbedder(settings.embedding_model, settings.embedding_revision, settings.model_cache_dir).info
    print(f"{info.name}@{info.revision} ({info.dimensions} dimensions) cached in {settings.model_cache_dir}")
    reranker = FastEmbedReranker(settings.reranker_model, settings.reranker_revision, settings.model_cache_dir).info
    print(f"{reranker.name}@{reranker.revision} cached in {settings.model_cache_dir}")
