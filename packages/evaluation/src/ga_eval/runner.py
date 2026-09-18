"""Runs evaluation cases against the API and writes run.json, cases.jsonl and report.md."""

import json
import platform
import subprocess
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import metrics, tokens
from ga_eval.client import ApiClient
from ga_eval.dataset import Dataset
from ga_eval.metrics import Result


def run(dataset: Dataset, client: ApiClient, strategies: list[str], k: int, splits: set[str]) -> dict:
    cases = [c for c in dataset.cases if c.split in splits]
    records: list[dict] = []
    policy_versions: set[str] = set()
    for case in cases:
        token = tokens.mint(dataset.root, case.principal, dataset.principals[case.principal], scope="query debug")
        spans = [dataset.span(e) for e in case.evidence]
        for strategy in strategies:
            response = client.search(token, case.query, strategy, k)
            policy_versions.add(response["policyVersion"])
            results = [Result(r["documentKey"], r["versionNo"], r["charStart"], r["charEnd"], r["rank"]) for r in response["results"]]
            record = {
                "case": case.id,
                "split": case.split,
                "strategy": strategy,
                "tags": case.tags,
                "must_abstain": case.must_abstain,
                "violations": metrics.violations(results, dataset.visibility[case.principal]),
                "results": [[r.document, r.version, r.start, r.end, r.rank] for r in results],
            }
            if spans:
                record |= {
                    "recall@5": metrics.recall_at(5, results, spans),
                    "recall@10": metrics.recall_at(10, results, spans),
                    "mrr@10": metrics.reciprocal_rank(results, spans),
                }
            records.append(record)
    return {
        "run": {
            "dataset_version": dataset.version,
            "git_sha": _git_sha(dataset.root),
            "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "strategies": strategies,
            "k": k,
            "splits": sorted(splits),
            "case_count": len(cases),
            "policy_versions": sorted(policy_versions),
            "platform": platform.platform(),
            "summary": summarize(records, strategies),
        },
        "cases": records,
    }


def summarize(records: list[dict], strategies: list[str]) -> dict:
    summary = {}
    for strategy in strategies:
        rows = [r for r in records if r["strategy"] == strategy]
        answerable = [r for r in rows if "recall@10" in r]
        summary[strategy] = {
            "answerable_cases": len(answerable),
            "recall@5": metrics.mean([r["recall@5"] for r in answerable]),
            "recall@10": metrics.mean([r["recall@10"] for r in answerable]),
            "mrr@10": metrics.mean([r["mrr@10"] for r in answerable]),
            "security_violations": sum(len(r["violations"]) for r in rows),
        }
    return summary


def write(output: dict, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "run.json").write_text(json.dumps(output["run"], indent=2) + "\n")
    (out_dir / "cases.jsonl").write_text("".join(json.dumps(r) + "\n" for r in output["cases"]))
    (out_dir / "report.md").write_text(render(output))


def render(output: dict) -> str:
    run_info, records = output["run"], output["cases"]
    lines = [
        f"# Retrieval evaluation — dataset {run_info['dataset_version']}",
        "",
        f"Demo benchmark on a small fictional corpus; not representative of production quality. "
        f"Commit `{run_info['git_sha']}`, {run_info['case_count']} cases ({', '.join(run_info['splits'])}), "
        f"k={run_info['k']}, policy {', '.join(run_info['policy_versions'])}, generated {run_info['created_at']}.",
        "",
        "| Strategy | Answerable cases | Recall@5 | Recall@10 | MRR@10 | Security violations |",
        "|---|---|---|---|---|---|",
    ]
    for strategy, s in run_info["summary"].items():
        scores = " | ".join(f"{s[m]:.3f}" for m in ("recall@5", "recall@10", "mrr@10"))
        lines.append(f"| `{strategy}` | {s['answerable_cases']} | {scores} | {s['security_violations']} |")
    lines += ["", "## Recall@10 by tag", "", "| Tag | " + " | ".join(f"`{s}`" for s in run_info["strategies"]) + " |"]
    lines.append("|---|" + "---|" * len(run_info["strategies"]))
    by_tag: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    for r in records:
        if "recall@10" in r:
            for tag in r["tags"]:
                by_tag[tag][r["strategy"]].append(r["recall@10"])
    for tag in sorted(by_tag):
        cells = [f"{metrics.mean(by_tag[tag][s]):.2f} (n={len(by_tag[tag][s])})" for s in run_info["strategies"]]
        lines.append(f"| {tag} | " + " | ".join(cells) + " |")
    misses = [r for r in records if r.get("recall@10", 1.0) < 1.0 or r["violations"]]
    lines += ["", "## Misses and violations", ""]
    lines += [f"- `{r['strategy']}` · `{r['case']}` · recall@10={r.get('recall@10', 'n/a')} · violations={r['violations'] or 'none'}" for r in misses]
    if not misses:
        lines.append("None.")
    return "\n".join(lines) + "\n"


def _git_sha(path: Path) -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=path, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
