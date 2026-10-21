"""Runtime settings for the model service, read once from environment variables."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """Each model is pinned by name and by the Hugging Face commit of its weights. Changing a revision changes the index profile (ADR-0007):
    vectors made with the old weights are not comparable with new ones."""

    embedding_model: str
    embedding_revision: str
    reranker_model: str
    reranker_revision: str
    model_cache_dir: str
    max_texts_per_request: int
    max_chars_per_text: int
    max_passages_per_request: int
    # Admission by lane (ADR-0010): concurrent computations, requests allowed to wait, and how long a request without a deadline waits.
    # Only admitted work takes a worker thread, so the slots of all lanes (12 by default) bound the threads in use. Eight query slots were
    # measured as fast as no limit under 16 clients; two were 60% slower.
    query_slots: int = 8
    query_queue: int = 16
    query_wait_seconds: float = 2.0
    passage_slots: int = 1
    passage_queue: int = 4
    passage_wait_seconds: float = 30.0
    rerank_slots: int = 1
    rerank_queue: int = 4
    rerank_wait_seconds: float = 3.0
    tokenize_slots: int = 2
    tokenize_queue: int = 8
    tokenize_wait_seconds: float = 30.0

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            embedding_model=os.getenv("GA_EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5"),
            embedding_revision=os.getenv("GA_EMBEDDING_REVISION", "aa8f8b060edb00e03bfdd08813a2949946c8ba55"),
            reranker_model=os.getenv("GA_RERANKER_MODEL", "Xenova/ms-marco-MiniLM-L-12-v2"),
            reranker_revision=os.getenv("GA_RERANKER_REVISION", "42a4a787e30451cf9dbd09080c2a5b8dde332c1e"),
            model_cache_dir=os.getenv("GA_MODEL_CACHE_DIR", os.path.expanduser("~/.cache/grounded-access/models")),
            max_texts_per_request=int(os.getenv("GA_MAX_TEXTS_PER_REQUEST", "128")),
            max_chars_per_text=int(os.getenv("GA_MAX_CHARS_PER_TEXT", "8000")),
            max_passages_per_request=int(os.getenv("GA_MAX_PASSAGES_PER_REQUEST", "64")),
            query_slots=int(os.getenv("GA_QUERY_SLOTS", "8")),
            query_queue=int(os.getenv("GA_QUERY_QUEUE", "16")),
            query_wait_seconds=float(os.getenv("GA_QUERY_WAIT_SECONDS", "2")),
            passage_slots=int(os.getenv("GA_PASSAGE_SLOTS", "1")),
            passage_queue=int(os.getenv("GA_PASSAGE_QUEUE", "4")),
            passage_wait_seconds=float(os.getenv("GA_PASSAGE_WAIT_SECONDS", "30")),
            rerank_slots=int(os.getenv("GA_RERANK_SLOTS", "1")),
            rerank_queue=int(os.getenv("GA_RERANK_QUEUE", "4")),
            rerank_wait_seconds=float(os.getenv("GA_RERANK_WAIT_SECONDS", "3")),
            tokenize_slots=int(os.getenv("GA_TOKENIZE_SLOTS", "2")),
            tokenize_queue=int(os.getenv("GA_TOKENIZE_QUEUE", "8")),
            tokenize_wait_seconds=float(os.getenv("GA_TOKENIZE_WAIT_SECONDS", "30")),
        )
