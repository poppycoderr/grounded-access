from ga_eval import runner


def record(case, strategy, split, recall, violations=(), hard=None):
    row = {"case": case, "strategy": strategy, "split": split, "tags": ["x"], "violations": list(violations)}
    if recall is not None:
        row |= {"recall@5": recall, "recall@10": recall, "mrr@10": recall, "ndcg@10": recall}
    if hard is not None:
        row |= {"hard_negative_rank": 1 if hard else None, "hard_negative_above_evidence": hard}
    return row


def test_report_separates_test_from_dev_and_compares_strategies_on_test_only():
    strategies = ["sparse-only", "dense-only"]
    records = [
        record("a", "sparse-only", "test", 0.0, hard=True),
        record("a", "dense-only", "test", 1.0, hard=False),
        record("b", "sparse-only", "test", 1.0),
        record("b", "dense-only", "test", 1.0),
        record("d", "sparse-only", "dev", 0.0),
        record("d", "dense-only", "dev", 0.0, violations=["secret"]),
    ]
    run_info = {
        "dataset_version": "v1",
        "git_sha": "abc",
        "case_count": 3,
        "splits": ["dev", "test"],
        "k": 10,
        "policy_versions": ["p1"],
        "created_at": "2026-09-21T00:00:00+00:00",
        "strategies": strategies,
        "bootstrap": {"samples": 10000, "seed": 1},
        "summary": {split: runner.summarize([r for r in records if r["split"] == split], strategies) for split in ["dev", "test"]},
        "comparisons": runner.compare([r for r in records if r["split"] == "test"], strategies),
        "security_violations": 1,
    }

    report = runner.render({"run": run_info, "cases": records})

    assert "## Results (test split)" in report
    assert "## Tuning split (dev) — not a result" in report
    assert "| `sparse-only` | 2 | 0.500 [" in report
    assert "| `sparse-only` | `dense-only` | 2 | +0.500" in report
    assert "no detectable difference" in report
    assert "| `sparse-only` | 1 | 1 |" in report
    assert "violations=['secret']" in report
