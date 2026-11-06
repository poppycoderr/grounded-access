"""How much of exact search an approximate vector index returns, per principal (ADR-0002, v0.2 plan step 1c).

Both inputs are `ga-eval load-test --record-results` runs of the same requests in the same order, one with exact search and one with an
index. The exact run is the reference: recall@k is the share of its top k that the candidate also returned, over every search of a
principal. A candidate that returns fewer results than exact search did is counted separately, because that is how filtering after an
index scan shows: the index returns its nearest rows, the predicate removes most of them, and too few are left.

Recall by identity is harsh when many chunks lie at almost the same distance from a query: exact search breaks those near-ties by document
key, an index by whatever it reached first. Distance recall is reported next to it: the share of the candidate's top k that is at least as
close as exact search's k-th result.
"""

import json
from pathlib import Path

from ga_eval.load import percentile

SCORE_TOLERANCE = 1e-6


class MismatchedRunsError(ValueError):
    """The two runs did not send the same searches, so they cannot be compared search by search."""


def compare(exact_dir: Path, candidate_dir: Path, k: int) -> dict:
    exact = _load(exact_dir)
    candidate = _load(candidate_dir)
    if [(r["case"], r["principal"], r["strategy"]) for r in exact] != [(r["case"], r["principal"], r["strategy"]) for r in candidate]:
        missing = sorted({r["case"] for r in exact} ^ {r["case"] for r in candidate})
        raise MismatchedRunsError(f"the runs sent different searches (cases in one run only: {', '.join(missing) or 'none, but in another order'})")
    totals: dict[str, dict] = {}
    for reference, measured in zip(exact, candidate, strict=True):
        if reference["status"] != 200 or measured["status"] != 200:
            raise MismatchedRunsError(f"case {reference['case']} was not answered in both runs")
        expected = [tuple(r[:3]) for r in reference["results"][:k]]
        returned = {tuple(r[:3]) for r in measured["results"][:k]}
        entry = totals.setdefault(
            reference["principal"], {"searches": 0, "expected": 0, "found": 0, "close_enough": 0, "fewer_results": 0, "empty": 0}
        )
        entry["searches"] += 1
        entry["expected"] += len(expected)
        entry["found"] += sum(1 for r in expected if r in returned)
        if expected:
            # Scores are 1 - cosine distance; a result at least as close as the exact k-th counts, with a margin for rounding
            threshold = min(r[3] for r in reference["results"][:k]) - SCORE_TOLERANCE
            entry["close_enough"] += min(len(expected), sum(1 for r in measured["results"][:k] if r[3] >= threshold))
        entry["fewer_results"] += 1 if len(returned) < len(expected) else 0
        entry["empty"] += 1 if expected and not returned else 0
    return {
        "k": k,
        "principals": {p: _rate(t) for p, t in sorted(totals.items())},
        "all": _rate(
            {key: sum(t[key] for t in totals.values()) for key in ("searches", "expected", "found", "close_enough", "fewer_results", "empty")}
        ),
        "latency": {"exact": _latency(exact), "candidate": _latency(candidate)},
        "exact_score_spread_p50": percentile(sorted(r["results"][0][3] - r["results"][:k][-1][3] for r in exact if r["results"]), 0.5),
    }


def render(comparison: dict, exact_name: str, candidate_name: str) -> str:
    lines = [
        f"# {candidate_name} against {exact_name}",
        "",
        f"Recall@{comparison['k']}: the share of exact search's top {comparison['k']} that `{candidate_name}` also returned. Distance recall: the "
        f"share of `{candidate_name}`'s top {comparison['k']} at least as close as exact search's {comparison['k']}th result.",
        "",
        "| Principal | Searches | Recall | Distance recall | Fewer results | Empty |",
        "|---|---|---|---|---|---|",
    ]
    for principal, r in [*comparison["principals"].items(), ("all", comparison["all"])]:
        lines.append(f"| {principal} | {r['searches']} | {r['recall']:.3f} | {r['distance_recall']:.3f} | {r['fewer_results']} | {r['empty']} |")
    exact, candidate = comparison["latency"]["exact"], comparison["latency"]["candidate"]
    lines += [
        "",
        f"Latency, p50 / p95 ms: `{exact_name}` {exact['p50_ms']} / {exact['p95_ms']}, `{candidate_name}` {candidate['p50_ms']} / "
        f"{candidate['p95_ms']}.",
        "",
        f"Median spread between exact search's first and {comparison['k']}th score: {comparison['exact_score_spread_p50']:.4f}.",
        "",
    ]
    return "\n".join(lines)


def _load(directory: Path) -> list[dict]:
    records = [json.loads(line) for line in (directory / "requests.jsonl").read_text().splitlines() if line]
    if any("results" not in r for r in records if r["status"] == 200):
        raise MismatchedRunsError(f"{directory} was not recorded with --record-results")
    return records


def _rate(totals: dict) -> dict:
    return {
        "searches": totals["searches"],
        "recall": totals["found"] / totals["expected"] if totals["expected"] else 1.0,
        "distance_recall": totals["close_enough"] / totals["expected"] if totals["expected"] else 1.0,
        "fewer_results": totals["fewer_results"],
        "empty": totals["empty"],
    }


def _latency(records: list[dict]) -> dict:
    ordered = sorted(r["latency_ms"] for r in records)
    return {"p50_ms": percentile(ordered, 0.5), "p95_ms": percentile(ordered, 0.95)}
