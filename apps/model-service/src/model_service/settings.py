"""Runtime settings for the model service, read once from environment variables."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    embedding_model: str
    model_cache_dir: str
    max_texts_per_request: int
    max_chars_per_text: int

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            embedding_model=os.getenv("GA_EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5"),
            model_cache_dir=os.getenv("GA_MODEL_CACHE_DIR", os.path.expanduser("~/.cache/grounded-access/models")),
            max_texts_per_request=int(os.getenv("GA_MAX_TEXTS_PER_REQUEST", "128")),
            max_chars_per_text=int(os.getenv("GA_MAX_CHARS_PER_TEXT", "8000")),
        )
