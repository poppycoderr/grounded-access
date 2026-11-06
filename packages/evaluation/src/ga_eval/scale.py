"""The summary of a scale run (`scripts/scale-run`, v0.2 plan step 1c), rendered from the files the run wrote: for each corpus size the
chunk count, each load-test run's latency and checks, the per-stage timings from the execution records, and the audit tables' growth."""

import csv
import json
from pathlib import Path


def summarize(directory: Path) -> dict:
    sizes = []
    for size_dir in sorted(directory.glob("size-*"), key=lambda p: int(p.name.split("-")[1])):
        runs = []
        for run_dir in sorted(size_dir.glob("workers-*"), key=lambda p: int(p.name.split("-")[1])):
            info = json.loads((run_dir / "run.json").read_text())
            with (run_dir / "stages.csv").open() as f:
                stages = {row["strategy"]: row for row in csv.DictReader(f)}
            audit = {row[0]: row[1:] for row in csv.reader((run_dir / "audit.csv").open())}
            searches = info["requests"]
            # An interrupted run leaves the audit reading after it empty; its growth is then unknown, not zero
            known = all(len(audit.get(side, [])) == 2 and all(audit[side]) for side in ("before", "after"))
            grown_bytes = int(audit["after"][0]) - int(audit["before"][0]) if known else None
            grown_rows = int(audit["after"][1]) - int(audit["before"][1]) if known else None
            runs.append(
                {
                    "workers": info["workers"],
                    "requests": searches,
                    "requests_per_second": info["requests_per_second"],
                    "failed": info["failed"],
                    "degraded": info["degraded"],
                    "security_violations": info["security_violations"],
                    "strategies": info["strategies"],
                    "stages": stages,
                    "audit_rows_per_1000": round(grown_rows * 1000 / searches) if searches and grown_rows is not None else None,
                    "audit_kib_per_1000": round(grown_bytes * 1000 / searches / 1024) if searches and grown_bytes is not None else None,
                    "cpu": info["cpu"],
                    "git_sha": info["git_sha"],
                }
            )
        sizes.append(
            {
                "distractor_documents": int(size_dir.name.split("-")[1]),
                "chunks": int((size_dir / "chunks.txt").read_text().strip()),
                "runs": runs,
            }
        )
    return {"sizes": sizes}


def render(summary: dict) -> str:
    first = next((run for size in summary["sizes"] for run in size["runs"]), None)
    lines = [
        "# Scale run",
        "",
        f"Rendered from the files of this directory by `ga-eval scale-report`. Client commit `{first['git_sha'] if first else '?'}`, "
        f"client CPU `{first['cpu'] if first else '?'}`. Every result was checked against the visible set of the principal that asked, "
        "labelled or generated.",
        "",
        "## Latency per request, as the client measured it",
        "",
        "| Chunks | Clients | Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for size in summary["sizes"]:
        for run in size["runs"]:
            for strategy, s in run["strategies"].items():
                failed = sum(count for status, count in s["statuses"].items() if status != "200")
                degraded = sum(s["degraded"].values())
                lines.append(
                    f"| {size['chunks']:,} | {run['workers']} | `{strategy}` | {s['requests']} | {failed} | {degraded} | {s['violations']} "
                    f"| {s['p50_ms']} | {s['p95_ms']} | {s['max_ms']} |"
                )
    lines += [
        "",
        "## Stage timings from the execution records",
        "",
        "Milliseconds, p50 / p95. The sparse stage is its SQL statement. The dense stage includes the query embedding, which does not depend "
        "on the corpus size.",
        "",
        "| Chunks | Clients | Strategy | Sparse p50 / p95 | Dense p50 / p95 |",
        "|---|---|---|---|---|",
    ]
    for size in summary["sizes"]:
        for run in size["runs"]:
            for strategy, row in run["stages"].items():
                lines.append(
                    f"| {size['chunks']:,} | {run['workers']} | `{strategy}` | {_ms(row['sparse_p50_ms'])} / {_ms(row['sparse_p95_ms'])} "
                    f"| {_ms(row['dense_p50_ms'])} / {_ms(row['dense_p95_ms'])} |"
                )
    lines += [
        "",
        "## Throughput and audit growth",
        "",
        "| Chunks | Clients | Requests per second | Audit rows per 1,000 searches | Audit KiB per 1,000 searches |",
        "|---|---|---|---|---|",
    ]
    for size in summary["sizes"]:
        for run in size["runs"]:
            growth = " | ".join("–" if run[key] is None else str(run[key]) for key in ("audit_rows_per_1000", "audit_kib_per_1000"))
            lines.append(f"| {size['chunks']:,} | {run['workers']} | {run['requests_per_second']} | {growth} |")
    return "\n".join(lines) + "\n"


def _ms(value: str) -> str:
    return "–" if value in ("", None) else f"{float(value):g}"
