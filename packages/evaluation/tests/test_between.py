import json
from pathlib import Path

import pytest

from ga_eval import between


def write_run(directory: Path, dataset: str, ranks: dict[str, float]) -> Path:
    directory.mkdir()
    run = {"dataset_version": dataset, "git_sha": "abc1234", "strategies": ["dense-only"], "chunker_versions": ["markdown/2"], "plans": {}}
    (directory / "run.json").write_text(json.dumps(run))
    records = [
        {"case": case, "strategy": "dense-only", "split": "test", "recall@10": 1.0, "mrr@10": mrr, "ndcg@10": mrr} for case, mrr in ranks.items()
    ] + [{"case": "must-abstain", "strategy": "dense-only", "split": "test"}]
    (directory / "cases.jsonl").write_text("".join(json.dumps(r) + "\n" for r in records))
    return directory


def test_the_difference_is_paired_per_case_and_names_the_cases_that_moved(tmp_path):
    before = write_run(tmp_path / "before", "v4", {"a": 0.5, "b": 1.0, "c": 0.25})
    after = write_run(tmp_path / "after", "v4", {"a": 1.0, "b": 1.0, "c": 0.5})

    row = between.compare(before, after, "test")["strategies"][0]

    assert row["cases"] == 3
    assert row["mrr@10"]["before"] == pytest.approx(0.583, abs=0.001)
    assert row["mrr@10"]["difference"]["mean"] == pytest.approx(0.25)
    assert row["changed_cases"] == ["a", "c"]
    assert row["recall@10"]["verdict"] == "no detectable difference"


def test_runs_on_different_datasets_are_not_compared(tmp_path):
    before = write_run(tmp_path / "before", "v3", {"a": 1.0})
    after = write_run(tmp_path / "after", "v4", {"a": 1.0})

    with pytest.raises(between.IncomparableRunsError, match="v3 and v4"):
        between.compare(before, after, "test")


def test_the_rendered_table_shows_before_after_and_the_interval(tmp_path):
    before = write_run(tmp_path / "before", "v4", {"a": 0.5, "b": 0.5})
    after = write_run(tmp_path / "after", "v4", {"a": 1.0, "b": 1.0})

    rendered = between.render(between.compare(before, after, "test"))

    assert "| `dense-only` | 2 | 1.000 → 1.000 |" in rendered
    assert "0.500 → 1.000 | +0.500 [+0.50, +0.50] — better | 2 |" in rendered
