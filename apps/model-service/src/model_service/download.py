"""Fetches the configured model into the cache directory; run at image build time so the service starts offline."""

from model_service.embedding import FastEmbedEmbedder
from model_service.settings import Settings

if __name__ == "__main__":
    settings = Settings.from_env()
    info = FastEmbedEmbedder(settings.embedding_model, settings.model_cache_dir).info
    print(f"{info.name}@{info.revision} ({info.dimensions} dimensions) cached in {settings.model_cache_dir}")
