from ga_eval import runner


def test_report_shows_every_strategy_and_lists_misses():
    records = [
        {"case": "a", "strategy": "sparse-only", "tags": ["x"], "violations": [], "recall@5": 0.0, "recall@10": 0.0, "mrr@10": 0.0},
        {"case": "a", "strategy": "dense-only", "tags": ["x"], "violations": [], "recall@5": 1.0, "recall@10": 1.0, "mrr@10": 1.0},
        {"case": "b", "strategy": "dense-only", "tags": ["y"], "violations": ["secret"]},
    ]
    strategies = ["sparse-only", "dense-only"]
    run_info = {
        "dataset_version": "v1",
        "git_sha": "abc",
        "case_count": 2,
        "splits": ["test"],
        "k": 10,
        "policy_versions": ["p1"],
        "created_at": "2026-09-18T00:00:00+00:00",
        "strategies": strategies,
        "summary": runner.summarize(records, strategies),
    }

    report = runner.render({"run": run_info, "cases": records})

    assert "| `sparse-only` | 1 | 0.000 | 0.000 | 0.000 | 0 |" in report
    assert "| `dense-only` | 1 | 1.000 | 1.000 | 1.000 | 1 |" in report
    assert "`sparse-only` · `a` · recall@10=0.0" in report
    assert "violations=['secret']" in report
