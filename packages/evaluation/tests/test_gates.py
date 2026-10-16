"""The gates must hold for every response the evaluator receives, and a comparison must compare what it says it compares."""

import argparse
import json
import shutil
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

import pytest

from ga_eval import between, cli, runner
from ga_eval import dataset as ds

ROOT = Path(__file__).resolve().parents[3]
DATA = ds.load(ROOT / "data")
CASE = next(c for c in DATA.cases if c.split == "test" and c.evidence and not c.out_of_scope_documents)
VISIBLE = DATA.visibility[CASE.principal]
HIDDEN = sorted(set(DATA.current_version) - VISIBLE)[0]
OUT_OF_SCOPE_CASE = next(c for c in DATA.cases if c.split == "test" and c.out_of_scope_documents)


def response(degraded: bool, document: str | None = None) -> dict:
    results = (
        [] if document is None else [{"documentKey": document, "versionNo": DATA.current_version[document], "charStart": 0, "charEnd": 1, "rank": 1}]
    )
    return {
        "policyVersion": "abac/1",
        "planHash": "plan",
        "plan": {},
        "degraded": ["dense_unavailable"] if degraded else [],
        "results": results,
    }


class ScriptedClient:
    """Lists every chunk the principal may see, then answers searches from a script, one response per call."""

    def __init__(self, script: list[dict], principal: str) -> None:
        self._script = list(script)
        self._visible = DATA.visibility[principal]

    def list_chunks(self, *args: object, **kwargs: object) -> tuple[str, list[dict]]:
        chunks = [
            {"documentKey": key, "versionNo": DATA.current_version[key], "charStart": 0, "charEnd": 1, "text": "", "chunkerVersion": "markdown/3"}
            for key in self._visible
        ]
        return "abac/1", chunks

    def search(self, *args: object) -> dict:
        return self._script.pop(0)


def run_case(case, script: list[dict]) -> dict:
    return runner.run(replace(DATA, cases=[case]), ScriptedClient(script, case.principal), ["hybrid-rrf"], 10, {"test"})


@pytest.mark.parametrize(
    "script",
    [
        [response(True, HIDDEN), response(False)],
        [response(True), response(True, HIDDEN), response(False)],
        [response(True), response(True), response(True, HIDDEN)],
    ],
    ids=["first attempt", "middle attempt", "last attempt"],
)
def test_an_unauthorized_result_in_any_attempt_fails_the_security_gate(script):
    output = run_case(CASE, script)

    assert output["run"]["security_violations"] == 1
    assert output["cases"][0]["violations"] == [HIDDEN]


def test_a_result_outside_the_scope_in_a_degraded_attempt_is_a_scope_failure():
    document = OUT_OF_SCOPE_CASE.out_of_scope_documents[0]

    output = run_case(OUT_OF_SCOPE_CASE, [response(True, document), response(False)])

    assert output["run"]["scope_failures"] == 1
    assert output["run"]["security_violations"] == 0


def test_a_clean_degraded_attempt_is_repeated_and_not_scored():
    output = run_case(CASE, [response(True), response(False)])

    assert output["run"]["repeated_degraded_requests"] == 1
    assert output["run"]["security_violations"] == 0
    assert output["cases"][0]["degraded_attempts"] == 1


def test_three_clean_degraded_attempts_invalidate_the_run_and_keep_what_was_measured():
    with pytest.raises(runner.DegradedRunError) as invalid:
        run_case(CASE, [response(True), response(True), response(True)])

    assert invalid.value.output["run"]["status"] == "invalid"
    assert "degraded" in invalid.value.output["run"]["invalid_reason"]


def test_an_invalid_run_is_written_before_the_command_fails(tmp_path):
    with pytest.raises(runner.DegradedRunError) as invalid:
        run_case(CASE, [response(True), response(True), response(True)])
    arguments = ["--data", str(ROOT / "data"), "run", "--out", str(tmp_path / "run")]

    with patch.object(cli.runner, "run", side_effect=invalid.value), patch.object(cli.ApiClient, "wait_until_ready"):
        assert cli.main(arguments) == 1

    assert json.loads((tmp_path / "run" / "run.json").read_text())["status"] == "invalid"
    assert "This run is invalid and incomplete" in (tmp_path / "run" / "report.md").read_text()


@pytest.mark.parametrize(("security", "scope", "expected"), [(0, 0, 0), (1, 0, 2), (0, 1, 3), (1, 1, 2)])
def test_the_answers_command_fails_on_security_and_on_scope_separately(tmp_path, security, scope, expected):
    output = {"run": {"security_violations": security, "scope_failures": scope}}

    def write(_, destination):
        destination.mkdir(parents=True)
        (destination / "answers-report.md").write_text("report\n")

    with patch.object(cli.answers, "run", return_value=output), patch.object(cli.answers, "write", side_effect=write):
        assert cli._answers(DATA, None, argparse.Namespace(split=["test"], out=tmp_path / "answers")) == expected


def write_run(directory: Path, rows: list[tuple[str, float]], **run: object) -> Path:
    directory.mkdir()
    info = {"dataset_version": "v4", "git_sha": "abc1234", "strategies": ["dense-only"], "k": 10, "security_violations": 0, "scope_failures": 0} | run
    (directory / "run.json").write_text(json.dumps(info))
    records = [{"case": case, "strategy": "dense-only", "split": "test", "recall@10": 1.0, "mrr@10": mrr, "ndcg@10": mrr} for case, mrr in rows]
    (directory / "cases.jsonl").write_text("".join(json.dumps(r) + "\n" for r in records))
    return directory


def test_a_comparison_refuses_runs_that_do_not_cover_the_same_cases(tmp_path):
    before = write_run(tmp_path / "before", [("a", 0.25), ("b", 1.0)])
    after = write_run(tmp_path / "after", [("a", 1.0)])

    with pytest.raises(between.IncomparableRunsError, match=r"missing from after: b"):
        between.compare(before, after, "test")

    subset = between.compare(before, after, "test", allow_subset=True)
    assert subset["subset"] == {"dense-only": {"missing_before": [], "missing_after": ["b"]}}
    assert "subset of the cases" in between.render(subset)


def test_a_comparison_refuses_duplicate_cases_an_empty_split_and_a_different_k(tmp_path):
    single = write_run(tmp_path / "single", [("a", 1.0)])
    duplicated = write_run(tmp_path / "duplicated", [("a", 1.0), ("a", 0.5)])
    other_k = write_run(tmp_path / "other-k", [("a", 1.0)], k=5)
    empty = write_run(tmp_path / "empty", [])

    with pytest.raises(between.IncomparableRunsError, match="appears more than once"):
        between.compare(single, duplicated, "test")
    with pytest.raises(between.IncomparableRunsError, match="k=10 and k=5"):
        between.compare(single, other_k, "test")
    with pytest.raises(between.IncomparableRunsError, match="no answerable test cases"):
        between.compare(empty, empty, "test")


def test_a_comparison_refuses_a_run_that_failed_its_gates(tmp_path):
    good = write_run(tmp_path / "good", [("a", 1.0)])
    leaking = write_run(tmp_path / "leaking", [("a", 1.0)], security_violations=1)
    invalid = write_run(tmp_path / "invalid", [("a", 1.0)], status="invalid")

    with pytest.raises(between.IncomparableRunsError, match="security violations"):
        between.compare(good, leaking, "test")
    with pytest.raises(between.IncomparableRunsError, match="invalid"):
        between.compare(invalid, good, "test")


def test_a_comparison_refuses_different_labels_and_marks_runs_that_do_not_record_them(tmp_path):
    first = write_run(tmp_path / "first", [("a", 1.0)], dataset_digest="d1", metrics_version="1")
    relabelled = write_run(tmp_path / "relabelled", [("a", 1.0)], dataset_digest="d2", metrics_version="1")
    legacy = write_run(tmp_path / "legacy", [("a", 1.0)])

    with pytest.raises(between.IncomparableRunsError, match="different labels"):
        between.compare(first, relabelled, "test")
    comparison = between.compare(legacy, first, "test")
    assert comparison["before"]["legacy"] == ["dataset_digest", "metrics_version"]
    assert comparison["after"]["legacy"] == []


def test_the_published_heading_context_comparison_is_reproduced_unchanged(tmp_path):
    reports = ROOT / "benchmarks" / "reports"
    published = json.loads((reports / "heading-context" / "comparison.json").read_text())

    comparison = between.compare(reports / "v0.1", reports / "heading-context", "test")

    assert comparison["strategies"] == published["strategies"]
    assert comparison["before"]["legacy"] == ["dataset_digest", "metrics_version"]


def test_a_new_run_records_the_digest_of_the_labels_and_the_metrics_version():
    output = run_case(CASE, [response(False)])

    assert output["run"]["dataset_digest"] == ds.digest(DATA)
    assert output["run"]["metrics_version"] == runner.METRICS_VERSION
    assert output["run"]["status"] == "complete"


def test_the_label_digest_changes_with_a_label_and_not_with_unrelated_files(tmp_path):
    copy = tmp_path / "data"
    shutil.copytree(ROOT / "data", copy)
    before = ds.digest(ds.load(copy))
    (copy / "README.md").write_text("unrelated\n")
    assert ds.digest(ds.load(copy)) == before

    visibility = copy / "eval" / DATA.version / "visibility.yaml"
    visibility.write_text(visibility.read_text() + "\n# a comment changes the bytes\n")
    assert ds.digest(ds.load(copy)) != before
