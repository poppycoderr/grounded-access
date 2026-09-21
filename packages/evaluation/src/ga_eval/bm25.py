"""Okapi BM25 over the chunks a principal may retrieve. It is the evaluation's reference row, not a system configuration: it shows how far
PostgreSQL FTS (no corpus statistics) falls short of a ranker that does use IDF, so a hybrid gain can be attributed correctly (ADR-0002)."""

import math
from collections import Counter
from dataclasses import dataclass

from ga_eval import text


@dataclass(frozen=True)
class Chunk:
    document: str
    version: int
    start: int
    end: int
    body: str


class Bm25Index:
    def __init__(self, chunks: list[Chunk], k1: float = 1.2, b: float = 0.75) -> None:
        self._chunks = chunks
        self._k1, self._b = k1, b
        self._terms = [Counter(text.tokens(c.body)) for c in chunks]
        self._lengths = [sum(t.values()) for t in self._terms]
        self._average = sum(self._lengths) / len(self._lengths) if chunks else 0.0
        frequency: Counter[str] = Counter()
        for terms in self._terms:
            frequency.update(terms.keys())
        n = len(chunks)
        # BM25+ style floor keeps the idf positive for terms that occur in more than half of the chunks
        self._idf = {term: math.log(1 + (n - df + 0.5) / (df + 0.5)) for term, df in frequency.items()}

    def search(self, query: str, k: int) -> list[tuple[Chunk, float]]:
        query_terms = text.tokens(query)
        scored = []
        for i, terms in enumerate(self._terms):
            score = 0.0
            for term in query_terms:
                tf = terms.get(term, 0)
                if tf:
                    norm = self._k1 * (1 - self._b + self._b * self._lengths[i] / self._average)
                    score += self._idf[term] * tf * (self._k1 + 1) / (tf + norm)
            if score > 0:
                scored.append((i, score))
        # Stable tie-break by corpus order (document key, version, ordinal), the same rule the SQL channels use
        scored.sort(key=lambda item: (-item[1], item[0]))
        return [(self._chunks[i], score) for i, score in scored[:k]]
