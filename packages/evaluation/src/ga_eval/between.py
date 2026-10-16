"""Compares two runs of the same cases: `ga-eval compare <before> <after>`.

A report compares strategies inside one run. A change to indexing or ranking has to be judged across two runs instead: the same strategy on
the same cases, before and after. The difference is paired per case, so a hard case counts against both runs alike.
"""

import json
from pathlib import Path

from ga_eval import stats
from ga_eval.runner import COMPARED_METRICS


class IncomparableRunsError(RuntimeError):
    """The two runs cannot be compared case by case: different datasets or labels, different cases, a different k or metric definition, or a
    run that failed its gates."""


# Fields a run records so that a comparison can check it was measured against the same labels with the same metrics. Runs made before they
# were recorded are compared, and the comparison says which fields were missing instead of assuming their values.
PROVENANCE = ["dataset_digest", "metrics_version"]

LISTED = 10


def load(run_dir: Path) -> tuple[dict, list[dict]]:
    return json.loads((run_dir / "run.json").read_text()), [json.loads(line) for line in (run_dir / "cases.jsonl").read_text().splitlines()]


def compare(before_dir: Path, after_dir: Path, split: str, allow_subset: bool = False) -> dict:
    """What may differ between the two runs is what the experiment changes: the index, the chunker, the models, the retrieval plans. What
    must not differ is checked here: dataset and labels, k, metric definition, the set of cases, and that neither run failed its gates."""
    before_run, before = load(before_dir)
    after_run, after = load(after_dir)
    for name, run in (("before", before_run), ("after", after_run)):
        _require_valid(name, run)
    _require_same(before_run, after_run)
    strategies = [s for s in before_run["strategies"] if s in after_run["strategies"]]
    first_all = _index("before", before, split, strategies)
    second_all = _index("after", after, split, strategies)
    rows = []
    subset: dict[str, dict] = {}
    for strategy in strategies:
        first_cases, second_cases = set(first_all[strategy]), set(second_all[strategy])
        if first_cases != second_cases:
            missing = {"missing_before": sorted(second_cases - first_cases), "missing_after": sorted(first_cases - second_cases)}
            if not allow_subset:
                raise IncomparableRunsError(
                    f"{strategy}: the runs do not cover the same {split} cases ("
                    + "; ".join(f"{side.replace('_', ' from ')}: {_listing(cases)}" for side, cases in missing.items() if cases)
                    + "); pass --allow-subset to compare the shared cases and mark the result as a subset"
                )
            subset[strategy] = missing
        first = {c: r for c, r in first_all[strategy].items() if "recall@10" in r}
        second = {c: r for c, r in second_all[strategy].items() if "recall@10" in r}
        shared = sorted(first.keys() & second.keys())
        for case in shared:
            for metric in COMPARED_METRICS:
                if metric not in first[case] or metric not in second[case]:
                    raise IncomparableRunsError(f"{strategy} · {case}: {metric} is missing from a run")
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
    if not any(row["cases"] for row in rows):
        raise IncomparableRunsError(f"the runs share no answerable {split} cases")
    comparison = {
        "split": split,
        "dataset_version": before_run["dataset_version"],
        "before": _describe(before_dir, before_run),
        "after": _describe(after_dir, after_run),
        "strategies": rows,
    }
    if subset:
        comparison["subset"] = subset
    return comparison


def _require_valid(name: str, run: dict) -> None:
    if run.get("status", "complete") != "complete":
        raise IncomparableRunsError(f"the {name} run is {run.get('status')}: {run.get('invalid_reason') or 'no reason recorded'}")
    if run.get("security_violations"):
        raise IncomparableRunsError(f"the {name} run has {run['security_violations']} security violations; its quality numbers are not comparable")
    if run.get("scope_failures"):
        raise IncomparableRunsError(f"the {name} run has {run['scope_failures']} scope failures; its quality numbers are not comparable")


def _require_same(before: dict, after: dict) -> None:
    if before["dataset_version"] != after["dataset_version"]:
        raise IncomparableRunsError(f"the runs use datasets {before['dataset_version']} and {after['dataset_version']}")
    if before.get("k") != after.get("k"):
        raise IncomparableRunsError(f"the runs use k={before.get('k')} and k={after.get('k')}")
    for field, what in (("dataset_digest", "different labels or corpus files"), ("metrics_version", "different metric definitions")):
        if field in before and field in after and before[field] != after[field]:
            raise IncomparableRunsError(f"the runs were scored against {what} ({field} {before[field]} and {after[field]})")


def _index(name: str, records: list[dict], split: str, strategies: list[str]) -> dict[str, dict[str, dict]]:
    """The records of one split by strategy and case. A case recorded twice for the same strategy makes the run ambiguous."""
    indexed: dict[str, dict[str, dict]] = {strategy: {} for strategy in strategies}
    for record in records:
        if record["split"] != split or record["strategy"] not in indexed:
            continue
        cases = indexed[record["strategy"]]
        if record["case"] in cases:
            raise IncomparableRunsError(f"in the {name} run, {record['case']} appears more than once for {record['strategy']}")
        cases[record["case"]] = record
    return indexed


def _listing(cases: list[str]) -> str:
    return ", ".join(cases[:LISTED]) + (f" and {len(cases) - LISTED} more" if len(cases) > LISTED else "")


def _describe(run_dir: Path, run: dict) -> dict:
    return {
        "directory": run_dir.name,
        "git_sha": run["git_sha"],
        "chunker_versions": run.get("chunker_versions", []),
        "plans": {strategy: sorted(hashes) for strategy, hashes in run.get("plans", {}).items()},
        "cpu": run.get("cpu"),
        "security_violations": run.get("security_violations"),
        "legacy": [field for field in PROVENANCE if field not in run],
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
    ]
    for side in ("before", "after"):
        if comparison[side]["legacy"]:
            lines += [
                f"The {side} run does not record {', '.join(comparison[side]['legacy'])}; it was made before runs recorded them, so equality of "
                "those was not checked.",
                "",
            ]
    if comparison.get("subset"):
        listed = "; ".join(
            f"`{strategy}`: missing before {_listing(missing['missing_before']) or 'none'}, "
            f"missing after {_listing(missing['missing_after']) or 'none'}"
            for strategy, missing in comparison["subset"].items()
        )
        lines += [f"**This compares a subset of the cases**, those both runs have. Not a full result. {listed}.", ""]
    lines += [
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
