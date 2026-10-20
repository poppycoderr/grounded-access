"""Keyword rankings are deterministic, so a run must reproduce the committed ones; a change has to be made on purpose (ADR-0008)."""

import argparse
import copy
from unittest.mock import patch

import pytest

from ga_eval import cli, rankings


def record(case: str, ranking: list[str], strategy: str = "sparse-only") -> dict:
    return {"case": case, "strategy": strategy, "split": "test", "results": [[key, 1, 0, 10, rank] for rank, key in enumerate(ranking, start=1)]}


RUN = {
    "status": "complete",
    "dataset_digest": "d1",
    "k": 10,
    "strategies": ["sparse-only", "dense-only", "bm25-reference"],
    "plans": {"sparse-only": {"hash-sparse": {}}, "dense-only": {"hash-dense": {}}},
    "reference_indexes_headings": [True],
    "security_violations": 0,
    "scope_failures": 0,
}
RECORDS = [
    record("a", ["policy", "handbook"]),
    record("b", ["handbook"]),
    record("a", ["handbook", "policy"], "bm25-reference"),
    record("a", ["anything"], "dense-only"),
]


def expected() -> dict:
    return rankings.expected_from(RUN, RECORDS)


def test_the_expected_rankings_hold_only_the_deterministic_strategies():
    recorded = expected()

    assert set(recorded["strategies"]) == {"sparse-only", "bm25-reference"}
    assert recorded["strategies"]["sparse-only"]["configuration"] == ["hash-sparse"]
    assert recorded["strategies"]["sparse-only"]["cases"]["a"] == [["policy", 1, 0, 10], ["handbook", 1, 0, 10]]


def test_a_run_that_ranks_as_recorded_passes_and_dense_differences_do_not_matter():
    dense_changed = [*RECORDS[:3], record("a", ["something else"], "dense-only")]

    result = rankings.check(RUN, dense_changed, expected())

    assert result["status"] == "passed"
    assert result["checked"] == ["sparse-only", "bm25-reference"]


def test_two_results_swapping_places_fail_the_check():
    swapped = [record("a", ["handbook", "policy"]), *RECORDS[1:]]

    result = rankings.check(RUN, swapped, expected())

    assert result["status"] == "failed"
    assert result["differences"] == [
        {
            "strategy": "sparse-only",
            "case": "a",
            "expected": [["policy", 1, 0, 10], ["handbook", 1, 0, 10]],
            "actual": [["handbook", 1, 0, 10], ["policy", 1, 0, 10]],
        }
    ]
    assert "expected policy@1:0, handbook@1:0, got handbook@1:0, policy@1:0" in rankings.render(result)


def test_a_case_without_a_recorded_ranking_fails():
    added = [*RECORDS, record("c", ["policy"])]

    assert rankings.check(RUN, added, expected())["differences"][0]["expected"] is None


def test_another_configuration_is_an_experiment_and_is_not_checked():
    experiment = copy.deepcopy(RUN) | {"plans": {"sparse-only": {"hash-without-headings": {}}}, "reference_indexes_headings": [False]}

    result = rankings.check(experiment, RECORDS, expected())

    assert result["status"] == "not applicable"
    assert "sparse-only ran with another configuration" in result["reason"]


def test_rankings_recorded_for_other_labels_or_another_k_fail():
    assert rankings.check(RUN | {"dataset_digest": "d2"}, RECORDS, expected())["status"] == "failed"
    assert rankings.check(RUN | {"k": 5}, RECORDS, expected())["status"] == "failed"


def test_only_a_complete_run_that_passed_its_gates_defines_the_expected_rankings():
    for broken in ({"status": "invalid"}, {"security_violations": 1}, {"scope_failures": 1}):
        with pytest.raises(rankings.RankingsUnavailableError):
            rankings.expected_from(RUN | broken, RECORDS)


def test_a_failed_ranking_check_makes_the_run_exit_with_4_after_writing_it(tmp_path):
    expected_file = tmp_path / "keyword-rankings.json"
    rankings.write(expected(), expected_file)
    swapped = [record("a", ["handbook", "policy"]), *RECORDS[1:]]
    output = {"run": RUN | {"summary": {}, "comparisons": []}, "cases": swapped}
    written = {}

    def write(out, directory):
        written["run"] = out["run"]
        directory.mkdir(parents=True)
        (directory / "report.md").write_text("report\n")

    args = argparse.Namespace(
        command="run", strategy=None, split=None, k=10, out=tmp_path / "out", expected_rankings=expected_file, data=tmp_path, base_url="http://x"
    )
    with (
        patch.object(cli.runner, "run", return_value=output),
        patch.object(cli.runner, "write", side_effect=write),
        patch.object(cli, "_validate", return_value=0),
        patch.object(cli.ds, "load"),
        patch.object(cli, "ApiClient"),
        patch.object(cli.argparse.ArgumentParser, "parse_args", return_value=args),
    ):
        assert cli.main([]) == 4
    assert written["run"]["ranking_check"]["status"] == "failed"
