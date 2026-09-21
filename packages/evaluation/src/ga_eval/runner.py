"""Runs evaluation cases against the API and writes run.json, cases.jsonl and report.md.

Headline numbers come from the `test` split only; `dev` exists for tuning and is reported separately so it can never be mistaken for a result.
"""

import itertools
import json
import platform
import subprocess
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import metrics, stats, tokens
from ga_eval.bm25 import Bm25Index, Chunk
from ga_eval.client import ApiClient
from ga_eval.dataset import Dataset
from ga_eval.metrics import Result

SYSTEM_STRATEGIES = ["sparse-only", "dense-only"]
REFERENCE = "bm25-reference"
QUALITY_METRICS = ["recall@5", "recall@10", "mrr@10", "ndcg@10"]
COMPARED_METRICS = ["recall@10", "mrr@10", "ndcg@10"]


def run(dataset: Dataset, client: ApiClient, strategies: list[str], k: int, splits: set[str]) -> dict:
    cases = [c for c in dataset.cases if c.split in splits]
    records: list[dict] = []
    policy_versions: set[str] = set()
    references: dict[str, Bm25Index] = {}
    for case in cases:
        token = tokens.mint(dataset.root, case.principal, dataset.principals[case.principal], scope="query debug")
        spans = [dataset.span(e) for e in case.evidence]
        for strategy in strategies:
            if strategy == REFERENCE:
                if case.principal not in references:
                    policy_version, chunks = client.list_chunks(token)
                    policy_versions.add(policy_version)
                    references[case.principal] = Bm25Index(
                        [Chunk(c["documentKey"], c["versionNo"], c["charStart"], c["charEnd"], c["text"]) for c in chunks]
                    )
                ranked = references[case.principal].search(case.query, k)
                results = [Result(c.document, c.version, c.start, c.end, rank) for rank, (c, _) in enumerate(ranked, start=1)]
            else:
                response = client.search(token, case.query, strategy, k)
                policy_versions.add(response["policyVersion"])
                results = [Result(r["documentKey"], r["versionNo"], r["charStart"], r["charEnd"], r["rank"]) for r in response["results"]]
            records.append(_record(case, strategy, results, spans, dataset.visibility[case.principal]))
    return {
        "run": {
            "dataset_version": dataset.version,
            "git_sha": _git_sha(dataset.root),
            "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "strategies": strategies,
            "reference_strategies": [s for s in strategies if s == REFERENCE],
            "k": k,
            "splits": sorted(splits),
            "case_count": len(cases),
            "policy_versions": sorted(policy_versions),
            "platform": platform.platform(),
            "bootstrap": {"samples": stats.SAMPLES, "seed": stats.SEED, "confidence": 0.95},
            "summary": {split: summarize([r for r in records if r["split"] == split], strategies) for split in sorted(splits)},
            "comparisons": compare([r for r in records if r["split"] == "test"], strategies),
            "security_violations": sum(len(r["violations"]) for r in records),
        },
        "cases": records,
    }


def _record(case, strategy: str, results: list[Result], spans, visible: set[str]) -> dict:
    record = {
        "case": case.id,
        "split": case.split,
        "strategy": strategy,
        "tags": case.tags,
        "must_abstain": case.must_abstain,
        "violations": metrics.violations(results, visible),
        "results": [[r.document, r.version, r.start, r.end, r.rank] for r in results],
    }
    if spans:
        record |= {
            "recall@5": metrics.recall_at(5, results, spans),
            "recall@10": metrics.recall_at(10, results, spans),
            "mrr@10": metrics.reciprocal_rank(results, spans),
            "ndcg@10": metrics.ndcg_at(10, results, spans),
        }
    if case.hard_negative_documents:
        negative = metrics.hard_negative_rank(results, set(case.hard_negative_documents))
        relevant = metrics.first_relevant_rank(results, spans) if spans else None
        record["hard_negative_rank"] = negative
        record["hard_negative_above_evidence"] = negative is not None and (relevant is None or negative < relevant)
    return record


def summarize(records: list[dict], strategies: list[str]) -> dict:
    summary = {}
    for strategy in strategies:
        rows = [r for r in records if r["strategy"] == strategy]
        answerable = [r for r in rows if "recall@10" in r]
        tempted = [r for r in rows if "hard_negative_above_evidence" in r]
        summary[strategy] = {
            "answerable_cases": len(answerable),
            **{m: _interval(stats.bootstrap([r[m] for r in answerable])) for m in QUALITY_METRICS},
            "hard_negative_cases": len(tempted),
            "hard_negative_above_evidence": sum(r["hard_negative_above_evidence"] for r in tempted),
            "security_violations": sum(len(r["violations"]) for r in rows),
        }
    return summary


def compare(records: list[dict], strategies: list[str]) -> list[dict]:
    """Every pair of strategies on the same answerable test cases; the difference is always second minus first."""
    by_strategy = {s: {r["case"]: r for r in records if r["strategy"] == s and "recall@10" in r} for s in strategies}
    comparisons = []
    for first, second in itertools.combinations(strategies, 2):
        shared = sorted(by_strategy[first].keys() & by_strategy[second].keys())
        entry: dict = {"first": first, "second": second, "cases": len(shared)}
        for metric in COMPARED_METRICS:
            difference = stats.paired([by_strategy[first][c][metric] for c in shared], [by_strategy[second][c][metric] for c in shared])
            entry[metric] = _interval(difference) | {"verdict": stats.verdict(difference)}
        comparisons.append(entry)
    return comparisons


def write(output: dict, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "run.json").write_text(json.dumps(output["run"], indent=2) + "\n")
    (out_dir / "cases.jsonl").write_text("".join(json.dumps(r) + "\n" for r in output["cases"]))
    (out_dir / "report.md").write_text(render(output))


def render(output: dict) -> str:
    run_info, records = output["run"], output["cases"]
    strategies = run_info["strategies"]
    lines = [
        f"# Retrieval evaluation — dataset {run_info['dataset_version']}",
        "",
        "Demo benchmark on a small fictional corpus; not representative of production quality. "
        f"Commit `{run_info['git_sha']}`, {run_info['case_count']} cases, k={run_info['k']}, "
        f"policy {', '.join(run_info['policy_versions'])}, generated {run_info['created_at']}. "
        f"Intervals are 95% percentile bootstraps over cases ({run_info['bootstrap']['samples']:,} samples, seed {run_info['bootstrap']['seed']}).",
        "",
    ]
    if REFERENCE in strategies:
        lines += [
            f"`{REFERENCE}` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks, "
            "to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.",
            "",
        ]
    for split in run_info["splits"]:
        summary = run_info["summary"][split]
        title = "Results (test split)" if split == "test" else "Tuning split (dev) — not a result"
        lines += [f"## {title}", "", "| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |", "|---|---|---|---|---|---|---|"]
        for strategy, s in summary.items():
            cells = " | ".join(_cell(s[m]) for m in QUALITY_METRICS)
            lines.append(f"| `{strategy}` | {s['answerable_cases']} | {cells} | {s['security_violations']} |")
        lines.append("")
    if run_info["comparisons"]:
        lines += [
            "## Paired comparisons (test split)",
            "",
            "Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.",
            "",
            "| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |",
            "|---|---|---|---|---|---|",
        ]
        for c in run_info["comparisons"]:
            cells = " | ".join(f"{_signed(c[m])} — {c[m]['verdict']}" for m in COMPARED_METRICS)
            lines.append(f"| `{c['first']}` | `{c['second']}` | {c['cases']} | {cells} |")
        lines.append("")
    test = run_info["summary"].get("test", {})
    if any(s["hard_negative_cases"] for s in test.values()):
        lines += [
            "## Hard negatives (test split)",
            "",
            "How often a document that looks relevant but is wrong ranks above the first correct evidence.",
            "",
            "| Strategy | Cases with hard negatives | Ranked above evidence |",
            "|---|---|---|",
        ]
        lines += [f"| `{name}` | {s['hard_negative_cases']} | {s['hard_negative_above_evidence']} |" for name, s in test.items()]
        lines.append("")
    lines += ["## Recall@10 by tag (test split)", "", "| Tag | " + " | ".join(f"`{s}`" for s in strategies) + " |"]
    lines.append("|---|" + "---|" * len(strategies))
    by_tag: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    for r in records:
        if r["split"] == "test" and "recall@10" in r:
            for tag in r["tags"]:
                by_tag[tag][r["strategy"]].append(r["recall@10"])
    for tag in sorted(by_tag):
        cells = [f"{metrics.mean(by_tag[tag][s]):.2f} (n={len(by_tag[tag][s])})" for s in strategies]
        lines.append(f"| {tag} | " + " | ".join(cells) + " |")
    misses = [r for r in records if r.get("recall@10", 1.0) < 1.0 or r["violations"]]
    lines += ["", "## Misses and violations", ""]
    lines += [
        f"- `{r['split']}` · `{r['strategy']}` · `{r['case']}` · recall@10={r.get('recall@10', 'n/a')} · violations={r['violations'] or 'none'}"
        for r in misses
    ]
    if not misses:
        lines.append("None.")
    return "\n".join(lines) + "\n"


def _interval(interval: stats.Interval) -> dict:
    return {"mean": interval.mean, "low": interval.low, "high": interval.high}


def _cell(value: dict) -> str:
    return f"{value['mean']:.3f} [{value['low']:.2f}, {value['high']:.2f}]"


def _signed(value: dict) -> str:
    return f"{value['mean']:+.3f} [{value['low']:+.2f}, {value['high']:+.2f}]"


def _git_sha(path: Path) -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=path, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
