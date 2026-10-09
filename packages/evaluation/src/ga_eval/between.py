"""Compares two runs of the same cases: `ga-eval compare <before> <after>`.

A report compares strategies inside one run. A change to indexing or ranking has to be judged across two runs instead: the same strategy on
the same cases, before and after. The difference is paired per case, so a hard case counts against both runs alike.
"""

import json
from pathlib import Path

from ga_eval import stats
from ga_eval.runner import COMPARED_METRICS


class IncomparableRunsError(RuntimeError):
    """The two runs were not made on the same dataset version, so a per-case difference means nothing."""


def load(run_dir: Path) -> tuple[dict, list[dict]]:
    return json.loads((run_dir / "run.json").read_text()), [json.loads(line) for line in (run_dir / "cases.jsonl").read_text().splitlines()]


def compare(before_dir: Path, after_dir: Path, split: str) -> dict:
    before_run, before = load(before_dir)
    after_run, after = load(after_dir)
    if before_run["dataset_version"] != after_run["dataset_version"]:
        raise IncomparableRunsError(f"the runs use datasets {before_run['dataset_version']} and {after_run['dataset_version']}")
    rows = []
    for strategy in [s for s in before_run["strategies"] if s in after_run["strategies"]]:
        first = {r["case"]: r for r in before if r["strategy"] == strategy and r["split"] == split and "recall@10" in r}
        second = {r["case"]: r for r in after if r["strategy"] == strategy and r["split"] == split and "recall@10" in r}
        shared = sorted(first.keys() & second.keys())
        row: dict = {"strategy": strategy, "cases": len(shared)}
        for metric in COMPARED_METRICS:
            difference = stats.paired([first[c][metric] for c in shared], [second[c][metric] for c in shared])
            row[metric] = {
                "before": sum(first[c][metric] for c in shared) / len(shared) if shared else 0.0,
                "after": sum(second[c][metric] for c in shared) / len(shared) if shared else 0.0,
                "difference": {"mean": difference.mean, "low": difference.low, "high": difference.high},
                "verdict": stats.verdict(difference),
            }
        row["changed_cases"] = sorted(c for c in shared if first[c]["mrr@10"] != second[c]["mrr@10"])
        rows.append(row)
    return {
        "split": split,
        "dataset_version": before_run["dataset_version"],
        "before": _describe(before_dir, before_run),
        "after": _describe(after_dir, after_run),
        "strategies": rows,
    }


def _describe(run_dir: Path, run: dict) -> dict:
    return {
        "directory": run_dir.name,
        "git_sha": run["git_sha"],
        "chunker_versions": run.get("chunker_versions", []),
        "plans": {strategy: sorted(hashes) for strategy, hashes in run.get("plans", {}).items()},
        "cpu": run.get("cpu"),
        "security_violations": run.get("security_violations"),
    }


def render(comparison: dict) -> str:
    before, after = comparison["before"], comparison["after"]
    lines = [
        f"# `{before['directory']}` → `{after['directory']}` — {comparison['split']} split, dataset {comparison['dataset_version']}",
        "",
        f"Before: commit `{before['git_sha']}`, chunker {', '.join(before['chunker_versions'])}. "
        f"After: commit `{after['git_sha']}`, chunker {', '.join(after['chunker_versions'])}. "
        "Each row is one strategy on the same cases in both runs; the difference is after minus before with a 95% interval, "
        "and counts only if the interval excludes zero.",
        "",
        "| Strategy | Cases | Recall@10 | ΔRecall@10 | MRR@10 | ΔMRR@10 | Cases whose rank changed |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in comparison["strategies"]:
        recall, mrr = row["recall@10"], row["mrr@10"]
        lines.append(
            f"| `{row['strategy']}` | {row['cases']} | {recall['before']:.3f} → {recall['after']:.3f} | {_delta(recall)} | "
            f"{mrr['before']:.3f} → {mrr['after']:.3f} | {_delta(mrr)} | {len(row['changed_cases'])} |"
        )
    return "\n".join(lines) + "\n"


def _delta(metric: dict) -> str:
    d = metric["difference"]
    return f"{d['mean']:+.3f} [{d['low']:+.2f}, {d['high']:+.2f}] — {metric['verdict']}"
