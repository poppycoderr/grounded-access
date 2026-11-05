"""Token counting with each model's own tokenizer (ADR-0009). The models accept at most `max_tokens` per input, special tokens included, and
fastembed would cut anything longer without saying so; the service counts first and refuses instead."""

import hashlib
import json
from collections.abc import Iterable
from pathlib import Path
from typing import Protocol

# The files a token counter reads, in the order they are hashed (ADR-0012).
TOKENIZER_FILES = ("tokenizer.json", "tokenizer_config.json")


class TokenCounter(Protocol):
    @property
    def max_tokens(self) -> int: ...

    @property
    def digest(self) -> str:
        """SHA-256 over the files this counter was built from; equal digests count alike."""
        ...

    def count(self, text: str, pair: str | None = None) -> int:
        """Tokens of the text (or of the text and its pair, as a cross-encoder reads them), special tokens included."""
        ...

    def fitting_chars(self, text: str, budget: int) -> int:
        """How many leading Unicode code points of the text fit in `budget` tokens, special tokens included. The prefix ends where a token
        ends. Python strings index code points, so this is the unit on the wire; Java converts it."""
        ...


class HuggingFaceTokenCounter:
    """The tokenizer a model was published with, read from its snapshot, without the truncation fastembed configures."""

    def __init__(self, snapshot: Path) -> None:
        from tokenizers import Tokenizer

        self._digest = file_digest(snapshot / name for name in TOKENIZER_FILES)
        self._tokenizer = Tokenizer.from_file(str(snapshot / "tokenizer.json"))
        self._tokenizer.no_truncation()
        self._tokenizer.no_padding()
        config = json.loads((snapshot / "tokenizer_config.json").read_text())
        limits = [config[key] for key in ("model_max_length", "max_length") if isinstance(config.get(key), int)]
        self._max_tokens = min(limits)

    @property
    def max_tokens(self) -> int:
        return self._max_tokens

    @property
    def digest(self) -> str:
        return self._digest

    def count(self, text: str, pair: str | None = None) -> int:
        return len(self._tokenizer.encode(text, pair).ids)

    def fitting_chars(self, text: str, budget: int) -> int:
        encoding = self._tokenizer.encode(text)
        if len(encoding.ids) <= budget:
            return len(text)
        content = [offset for offset, special in zip(encoding.offsets, encoding.special_tokens_mask, strict=True) if not special]
        allowed = budget - (len(encoding.ids) - len(content))
        return content[allowed - 1][1] if allowed > 0 else 0


def file_digest(paths: Iterable[Path]) -> str:
    """SHA-256 over the files in order, each preceded by its length, so that moving bytes from one file to the next changes it."""
    sha = hashlib.sha256()
    for path in paths:
        content = path.read_bytes()
        sha.update(len(content).to_bytes(8, "big"))
        sha.update(content)
    return sha.hexdigest()


def identity(counters: Iterable[TokenCounter]) -> str:
    """The chunking tokenizer identity (ADR-0012): what decides where the control plane cuts chunks. Ordered as the counters are applied."""
    return "sha256:" + hashlib.sha256("\n".join(c.digest for c in counters).encode()).hexdigest()
