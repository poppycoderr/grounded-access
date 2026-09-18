"""Pure metric functions over one case's ranked results. Relevance is decided by span overlap, so labels survive chunking changes."""

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


def violations(results: list[Result], visible: set[str]) -> list[str]:
    """Documents that were returned but are outside the principal's human-labelled visible set."""
    return sorted({r.document for r in results if r.document not in visible})


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0
