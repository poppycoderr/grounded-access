"""Pure metric functions over one case's ranked results. Relevance is decided by span overlap, so labels survive chunking changes."""

import math
from dataclasses import dataclass

from ga_eval.dataset import Span


@dataclass(frozen=True)
class Result:
    document: str
    version: int
    start: int
    end: int
    rank: int


def covers(result: Result, span: Span) -> bool:
    return result.document == span.document and result.version == span.version and result.start < span.end and span.start < result.end


def recall_at(k: int, results: list[Result], spans: list[Span]) -> float:
    """Fraction of evidence spans covered by at least one of the top-k results."""
    top = [r for r in results if r.rank <= k]
    return sum(any(covers(r, s) for r in top) for s in spans) / len(spans)


def reciprocal_rank(results: list[Result], spans: list[Span], k: int = 10) -> float:
    for result in sorted(results, key=lambda r: r.rank):
        if result.rank <= k and any(covers(result, s) for s in spans):
            return 1 / result.rank
    return 0.0


def evidence_coverage_at(k: int, results: list[Result], spans: list[Span]) -> float:
    """EC@k (metrics version 2, ADR-0008): every evidence span earns 1 / log2(1 + r), where r is the rank of the first result that covers it,
    or 0 if none in the top k does; the mean over the spans. A later result covering the same span adds nothing. The value is 1 only when the
    first result covers every span, so a case whose evidence lies in two documents cannot reach 1: compare EC between runs of the same cases,
    never against a perfect score."""
    first: dict[int, int] = {}
    for result in sorted(results, key=lambda r: r.rank):
        if result.rank > k:
            break
        for i, span in enumerate(spans):
            if i not in first and covers(result, span):
                first[i] = result.rank
    return sum(1 / math.log2(rank + 1) for rank in first.values()) / len(spans)


def ndcg_at(k: int, results: list[Result], spans: list[Span]) -> float:
    """Metrics version 1 only, kept so version 1 runs can still be recomputed: binary-gain nDCG, where a result earns gain only for evidence
    spans no higher-ranked result has covered yet. Its ideal assumes one result per span, so one chunk covering two spans scores below two
    chunks covering them (ADR-0008); version 2 reports evidence_coverage_at instead."""
    covered: set[int] = set()
    dcg = 0.0
    for result in sorted(results, key=lambda r: r.rank):
        if result.rank > k:
            break
        new = {i for i, span in enumerate(spans) if i not in covered and covers(result, span)}
        if new:
            covered |= new
            dcg += 1 / math.log2(result.rank + 1)
    ideal = sum(1 / math.log2(rank + 1) for rank in range(1, min(k, len(spans)) + 1))
    return dcg / ideal if ideal else 0.0


def hard_negative_rank(results: list[Result], hard_negatives: set[str]) -> int | None:
    """Rank of the first result from a document that looks relevant but is the wrong answer."""
    return next((r.rank for r in sorted(results, key=lambda r: r.rank) if r.document in hard_negatives), None)


def first_relevant_rank(results: list[Result], spans: list[Span]) -> int | None:
    return next((r.rank for r in sorted(results, key=lambda r: r.rank) if any(covers(r, s) for s in spans)), None)


def violations(results: list[Result], visible: set[str], current_version: dict[str, int]) -> list[str]:
    """Every returned chunk is checked: its document must be in the principal's human-labelled visible set, and it must come from the
    document's current version. A chunk of a replaced version is reported as `key@vN`, because its labels may differ from the current ones."""
    unauthorized = {r.document for r in results if r.document not in visible}
    stale = {f"{r.document}@v{r.version}" for r in results if r.document in visible and r.version != current_version[r.document]}
    return sorted(unauthorized | stale)


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0
