"""Recall of an approximate index against exact search, per principal, from two load-test runs that recorded their results (ADR-0002)."""

import json
from pathlib import Path

from ga_eval import ann


def write(directory: Path, requests: list[dict]) -> Path:
    directory.mkdir()
    (directory / "requests.jsonl").write_text("".join(json.dumps(r) + "\n" for r in requests))
    (directory / "run.json").write_text(json.dumps({"requests": len(requests)}))
    return directory


def request(case: str, principal: str, results: list[str], status: int = 200, latency: float = 10.0, scores: list[float] | None = None) -> dict:
    scores = scores or [1.0 - 0.1 * i for i in range(len(results))]
    return {
        "case": case,
        "principal": principal,
        "strategy": "dense-only",
        "status": status,
        "latency_ms": latency,
        "results": [[key, 1, 0, score] for key, score in zip(results, scores, strict=True)],
    }


def test_recall_counts_how_many_of_the_exact_top_k_the_candidate_returned_per_principal(tmp_path):
    exact = write(
        tmp_path / "exact",
        [
            request("c1", "alice", ["a", "b", "c", "d"]),
            request("c2", "mallory", ["x", "y"]),
            request("c3", "mallory", ["z"]),
        ],
    )
    candidate = write(
        tmp_path / "hnsw",
        [
            request("c1", "alice", ["a", "b", "c", "e"], latency=4.0),
            request("c2", "mallory", ["x"], latency=3.0),
            request("c3", "mallory", [], latency=2.0),
        ],
    )

    comparison = ann.compare(exact, candidate, k=10)

    assert comparison["principals"]["alice"] == {"searches": 1, "recall": 0.75, "distance_recall": 1.0, "fewer_results": 0, "empty": 0}
    assert comparison["principals"]["mallory"] == {"searches": 2, "recall": 1 / 3, "distance_recall": 1 / 3, "fewer_results": 2, "empty": 1}
    assert comparison["all"]["recall"] == 4 / 7
    assert comparison["latency"]["candidate"]["p50_ms"] == 3.0
    assert round(comparison["exact_score_spread_p50"], 6) == 0.1


def test_a_search_missing_from_either_run_is_refused_rather_than_scored(tmp_path):
    exact = write(tmp_path / "exact", [request("c1", "alice", ["a"]), request("c2", "alice", ["b"])])
    candidate = write(tmp_path / "hnsw", [request("c1", "alice", ["a"])])

    try:
        ann.compare(exact, candidate, k=10)
    except ann.MismatchedRunsError as mismatch:
        assert "c2" in str(mismatch)
    else:
        raise AssertionError("expected a mismatch")


def test_a_different_chunk_at_the_same_distance_is_a_miss_by_identity_but_not_by_distance(tmp_path):
    exact = write(tmp_path / "exact", [request("c1", "alice", ["a", "b"], scores=[0.70, 0.69])])
    tie = write(tmp_path / "tie", [request("c1", "alice", ["a", "twin-of-b"], scores=[0.70, 0.69])])
    farther = write(tmp_path / "farther", [request("c1", "alice", ["a", "far"], scores=[0.70, 0.50])])

    assert ann.compare(exact, tie, k=10)["all"]["recall"] == 0.5
    assert ann.compare(exact, tie, k=10)["all"]["distance_recall"] == 1.0
    assert ann.compare(exact, farther, k=10)["all"]["distance_recall"] == 0.5
