"""The scale summary is rendered from the files a scale run wrote, never typed in (v0.2 plan, step 1c)."""

import json
from pathlib import Path

from ga_eval import scale


def write_run(directory: Path, size: int, chunks: int, workers: int, p95: float) -> None:
    run = directory / f"size-{size}" / f"workers-{workers}"
    run.mkdir(parents=True)
    (directory / f"size-{size}" / "chunks.txt").write_text(f"{chunks}\n")
    strategy = {
        "requests": 10,
        "statuses": {"200": 9, "503": 1},
        "degraded": {"dense_unavailable": 2},
        "violations": 0,
        "p50_ms": 5.0,
        "p95_ms": p95,
        "max_ms": p95 + 1,
    }
    info = {
        "workers": workers,
        "requests": 10,
        "requests_per_second": 20.0,
        "failed": 1,
        "degraded": 2,
        "security_violations": 0,
        "strategies": {"sparse-only": strategy},
        "cpu": "Test CPU",
        "git_sha": "abc1234",
    }
    (run / "run.json").write_text(json.dumps(info))
    (run / "stages.csv").write_text("strategy,requests,sparse_p50_ms,sparse_p95_ms,dense_p50_ms,dense_p95_ms\nsparse-only,10,3,4.5,,\n")
    (run / "audit.csv").write_text("before,8192,0\nafter,16384,20\n")


def test_sizes_are_ordered_by_number_and_every_figure_comes_from_the_files(tmp_path):
    write_run(tmp_path, 4200, 33000, 1, 40.0)
    write_run(tmp_path, 400, 3400, 1, 12.0)
    write_run(tmp_path, 0, 328, 16, 9.0)

    summary = scale.summarize(tmp_path)
    report = scale.render(summary)

    assert [s["distractor_documents"] for s in summary["sizes"]] == [0, 400, 4200]
    run = summary["sizes"][2]["runs"][0]
    assert run["audit_rows_per_1000"] == 2000 and run["audit_kib_per_1000"] == 800
    assert "| 33,000 | 1 | `sparse-only` | 10 | 1 | 2 | 0 | 5.0 | 40.0 | 41.0 |" in report
    assert "| 328 | 16 | `sparse-only` | 3 / 4.5 | – / – |" in report
