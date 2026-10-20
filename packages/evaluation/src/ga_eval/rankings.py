"""The expected keyword rankings (ADR-0008): `sparse-only` and `bm25-reference` rank bit-identically on every machine, so their top-k for every
case is committed and a run that differs fails. Dense and reranked rankings can swap near-ties between CPUs and are not checked.

A change to keyword ranking is made on purpose: the pull request regenerates the file with `ga-eval rankings` and shows the evaluation
evidence. Nothing regenerates it automatically.
"""

import json
from pathlib import Path

CHECKED = ("sparse-only", "bm25-reference")

# What, besides the dataset and k, decides a checked strategy's ranking. A run with another configuration is an experiment, not a regression:
# the check does not apply to it.
CONFIGURATION = {"sparse-only": "plans", "bm25-reference": "reference_indexes_headings"}

LISTED = 10


class RankingsUnavailableError(RuntimeError):
    """The run cannot serve as the expected rankings."""


def expected_from(run: dict, records: list[dict]) -> dict:
    if run.get("status", "complete") != "complete" or run.get("security_violations") or run.get("scope_failures"):
        raise RankingsUnavailableError("only a complete run that passed its gates can define the expected rankings")
    strategies = [s for s in CHECKED if s in run["strategies"]]
    if not strategies:
        raise RankingsUnavailableError(f"the run has none of {', '.join(CHECKED)}")
    return {
        "dataset_digest": run["dataset_digest"],
        "k": run["k"],
        "strategies": {
            strategy: {
                "configuration": _configuration(run, strategy),
                "cases": {r["case"]: _ranking(r) for r in sorted(records, key=lambda r: r["case"]) if r["strategy"] == strategy},
            }
            for strategy in strategies
        },
    }


def check(run: dict, records: list[dict], expected: dict) -> dict:
    """`passed`, `failed` with the differing cases, or `not applicable` with the reason. A run on other labels or another k fails: the
    file has to be regenerated with the change that caused it."""
    if run.get("dataset_digest") != expected["dataset_digest"] or run.get("k") != expected["k"]:
        return {
            "status": "failed",
            "reason": "the expected rankings were recorded for another dataset or k; regenerate them with `ga-eval rankings`",
            "differences": [],
        }
    checked, skipped, differences = [], [], []
    for strategy, recorded in expected["strategies"].items():
        if strategy not in run["strategies"]:
            continue
        if _configuration(run, strategy) != recorded["configuration"]:
            skipped.append(f"{strategy} ran with another configuration")
            continue
        checked.append(strategy)
        for record in records:
            if record["strategy"] != strategy:
                continue
            want = recorded["cases"].get(record["case"])
            got = _ranking(record)
            if want != got:
                differences.append({"strategy": strategy, "case": record["case"], "expected": want, "actual": got})
    if not checked:
        return {"status": "not applicable", "reason": "; ".join(skipped) or "the run has no keyword strategy", "differences": []}
    return {
        "status": "failed" if differences else "passed",
        "reason": f"{len(differences)} case(s) ranked differently" if differences else None,
        "checked": checked,
        "skipped": skipped,
        "differences": differences,
    }


def render(result: dict) -> str:
    lines = ["## Keyword rankings", ""]
    if result["status"] == "passed":
        lines.append(f"Every case of {', '.join(f'`{s}`' for s in result['checked'])} ranked as the committed expected rankings say.")
    elif result["status"] == "not applicable":
        lines.append(f"Not checked: {result['reason']}.")
    else:
        lines.append(f"**Failed:** {result['reason']}. A change to keyword ranking regenerates the expected rankings in the same pull request.")
        for d in result["differences"][:LISTED]:
            lines.append(f"- `{d['strategy']}` · `{d['case']}`: expected {_short(d['expected'])}, got {_short(d['actual'])}")
        if len(result["differences"]) > LISTED:
            lines.append(f"- and {len(result['differences']) - LISTED} more")
    return "\n".join(lines) + "\n"


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def write(expected: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(expected, indent=1) + "\n")


def _configuration(run: dict, strategy: str):
    value = run.get(CONFIGURATION[strategy])
    return sorted(value.get(strategy, {})) if strategy == "sparse-only" else value


def _ranking(record: dict) -> list[list]:
    return [result[:4] for result in sorted(record["results"], key=lambda r: r[4])]


def _short(ranking: list[list] | None) -> str:
    if ranking is None:
        return "no recorded ranking"
    return ", ".join(f"{key}@{version}:{start}" for key, version, start, _ in ranking[:3]) + (" …" if len(ranking) > 3 else "")
