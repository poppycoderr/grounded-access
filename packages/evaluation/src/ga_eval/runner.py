"""Runs evaluation cases against the API and writes run.json, cases.jsonl and report.md.

Headline numbers come from the `test` split only; `dev` exists for tuning and is reported separately so it can never be mistaken for a result.
"""

import itertools
import json
import platform
import subprocess
import time
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import metrics, provenance, rankings, stats, tokens
from ga_eval.bm25 import Bm25Index, Chunk
from ga_eval.client import ApiClient
from ga_eval.dataset import Dataset, digest
from ga_eval.metrics import Result

SYSTEM_STRATEGIES = ["sparse-only", "dense-only", "hybrid-rrf", "hybrid-rrf-rerank"]
REFERENCE = "bm25-reference"
QUALITY_METRICS = ["recall@5", "recall@10", "mrr@10", "ec@10"]
COMPARED_METRICS = ["recall@10", "mrr@10", "ec@10"]

# The metrics a comparison pairs, by the metrics version a run recorded. Runs that recorded none are version 1.
COMPARED_BY_VERSION = {"1": ["recall@10", "mrr@10", "ndcg@10"], "2": COMPARED_METRICS}


# A degraded answer does not measure the strategy, so it is never scored. On a busy machine a single rerank call can exceed its timeout, so
# the request is repeated before the run is given up; the report says how often that happened.
ATTEMPTS = 3


# The meaning of the per-case metrics. A run records it, and a comparison of runs that measured different things is refused. Version 2
# replaced nDCG@10 with evidence coverage EC@10 (ADR-0008); version 1 reports stay as they were.
METRICS_VERSION = "2"

# No request is sent before measuring; the manifest says so, and latency figures include cold starts.
WARMUP_REQUESTS = 0


class InvalidRunError(RuntimeError):
    """The run cannot be a result. `output` holds what was measured until then, marked invalid, so that the evidence can be kept."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.output: dict | None = None


class DegradedRunError(InvalidRunError):
    """The system answered without part of its plan (for example without the dense channel) too often, so the result does not measure the
    strategy."""


class DriftError(InvalidRunError):
    """The server, the index, the reranker or the corpus changed between the first request and the last, so the run measured two systems."""


class VisibilityMismatchError(RuntimeError):
    """A principal cannot list a document the labels say it may see: the system and the hand-written labels disagree, so no result is valid."""


class ReloadedCorpusError(RuntimeError):
    """A document is at a later version than the dataset labels, so the corpus is not the one the cases were written for."""


class MixedChunkerError(RuntimeError):
    """The corpus was chunked by more than one chunker release, so a run would compare results of different chunkings."""


def chunker_release(version: str) -> str:
    """`markdown/2` and `text/2` are one release applied to two formats; `markdown/1` next to `markdown/2` is a partial re-index."""
    return version.rsplit("/", 1)[-1]


def check_versions(dataset: Dataset, listings: dict[str, tuple[str, list[dict]]]) -> None:
    """A version later than the labelled one means the corpus was changed after it was loaded. An earlier one is left to the security gate:
    the system is then serving a version that has been replaced."""
    for _, chunks in listings.values():
        for chunk in chunks:
            labelled = dataset.current_version.get(chunk["documentKey"])
            if labelled is not None and chunk["versionNo"] > labelled:
                raise ReloadedCorpusError(
                    f"{chunk['documentKey']} is at version {chunk['versionNo']} but the dataset labels version {labelled}; "
                    "reset the database (docker compose down -v), start the stack and load the corpus again"
                )


def run(dataset: Dataset, client: ApiClient, strategies: list[str], k: int, splits: set[str]) -> dict:
    cases = [c for c in dataset.cases if c.split in splits]
    records: list[dict] = []
    policy_versions: set[str] = set()
    tokens_by_principal = {p: tokens.mint(dataset.root, p, dataset.principals[p], scope="query debug") for p in sorted({c.principal for c in cases})}
    listings = {p: client.list_chunks(token, include_out_of_scope=True) for p, token in tokens_by_principal.items()}
    chunker_versions = sorted({c["chunkerVersion"] for _, chunks in listings.values() for c in chunks})
    if len({chunker_release(v) for v in chunker_versions}) > 1:
        raise MixedChunkerError(f"the corpus mixes chunker versions {', '.join(chunker_versions)}; re-index it with a fresh load before evaluating")
    check_versions(dataset, listings)
    visibility_check = check_visibility(dataset, listings)
    system_token = next(iter(tokens_by_principal.values()))
    observed = provenance.snapshot(client, system_token, listings)
    references: dict[tuple, Bm25Index] = {}
    repeated = 0
    reference_headings: set[bool] = set()
    plans: dict[str, dict[str, dict]] = {}

    def assemble(status: str, reason: str | None = None) -> dict:
        return {
            "run": {
                "status": status,
                "invalid_reason": reason,
                "dataset_version": dataset.version,
                "dataset_digest": digest(dataset),
                "metrics_version": METRICS_VERSION,
                "git_sha": _git_sha(dataset.root),
                "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
                "manifest": provenance.manifest(
                    observed,
                    dataset.version,
                    digest(dataset),
                    dataset.root,
                    {
                        "strategies": strategies,
                        "k": k,
                        "splits": sorted(splits),
                        "attempt_limit": ATTEMPTS,
                        "warmup_requests": WARMUP_REQUESTS,
                        "metrics_version": METRICS_VERSION,
                    },
                ),
                "strategies": strategies,
                "reference_strategies": [s for s in strategies if s == REFERENCE],
                "k": k,
                "splits": sorted(splits),
                "case_count": len(cases),
                "policy_versions": sorted(policy_versions),
                "chunker_versions": chunker_versions,
                "plans": plans,
                "repeated_degraded_requests": repeated,
                "reference_indexes_headings": sorted(reference_headings),
                "visibility_check": visibility_check,
                "platform": platform.platform(),
                "cpu": provenance.cpu_model(),
                "bootstrap": {"samples": stats.SAMPLES, "seed": stats.SEED, "confidence": 0.95},
                "summary": {split: summarize([r for r in records if r["split"] == split], strategies) for split in sorted(splits)},
                "comparisons": compare([r for r in records if r["split"] == "test"], strategies),
                "security_violations": sum(len(r["violations"]) for r in records) + sum(len(v["violations"]) for v in visibility_check.values()),
                "scope_failures": sum(len(r["scope_failures"]) for r in records),
            },
            "cases": records,
        }

    for case in cases:
        token = tokens_by_principal[case.principal]
        spans = [dataset.span(e) for e in case.evidence]
        as_of = case.as_of.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ") if case.as_of else None
        for strategy in strategies:
            if strategy == REFERENCE:
                # The reference ranks what the system could have returned for this request, so it lists under the same scope.
                scope = (case.principal, as_of, case.region)
                if scope not in references:
                    policy_version, chunks = client.list_chunks(token, as_of, case.region)
                    policy_versions.add(policy_version)
                    # The reference indexes what the system's keyword channel indexes, so the row keeps measuring ranking and not input.
                    headings = any(plan.get("sparseContext") for hashes in plans.values() for plan in hashes.values())
                    reference_headings.add(headings)
                    references[scope] = Bm25Index(
                        [
                            Chunk(
                                c["documentKey"],
                                c["versionNo"],
                                c["charStart"],
                                c["charEnd"],
                                f"{c.get('sectionPath', '')} {c['text']}" if headings else c["text"],
                            )
                            for c in chunks
                        ]
                    )
                ranked = references[scope].search(case.query, k)
                results = [Result(c.document, c.version, c.start, c.end, rank) for rank, (c, _) in enumerate(ranked, start=1)]
            else:
                # Every response is checked before anything else is decided about it. A degraded response is repeated rather than scored,
                # but one that returned something the principal may not see, or something out of scope, is kept as the case's result:
                # a later clean answer must not hide it.
                degraded_attempts = 0
                attempts = []
                for attempt in range(1, ATTEMPTS + 1):
                    started = time.perf_counter()
                    response = client.search(token, case.query, strategy, k, as_of, case.region)
                    latency_ms = (time.perf_counter() - started) * 1000
                    attempts.append({"degraded": response["degraded"], "latency_ms": round(latency_ms, 1)})
                    policy_versions.add(response["policyVersion"])
                    plans.setdefault(strategy, {})[response["planHash"]] = response["plan"]
                    results = [Result(r["documentKey"], r["versionNo"], r["charStart"], r["charEnd"], r["rank"]) for r in response["results"]]
                    record = _record(case, strategy, results, spans, dataset.visibility[case.principal], dataset.current_version)
                    if record["violations"] or record["scope_failures"] or not response["degraded"]:
                        break
                    if attempt == ATTEMPTS:
                        invalid = DegradedRunError(
                            f"case {case.id} · {strategy} was answered degraded ({', '.join(response['degraded'])}) "
                            f"{ATTEMPTS} times in a row; the run is invalid"
                        )
                        invalid.output = assemble("invalid", str(invalid))
                        raise invalid
                    degraded_attempts += 1
                    repeated += 1
                if response["degraded"]:
                    record["degraded"] = response["degraded"]
                record["degraded_attempts"] = degraded_attempts
                record["attempts"] = attempts
                record["latency_ms"] = round(latency_ms, 1)
                records.append(record)
                continue
            records.append(_record(case, strategy, results, spans, dataset.visibility[case.principal], dataset.current_version))
    final = provenance.snapshot(client, system_token, {p: client.list_chunks(t, include_out_of_scope=True) for p, t in tokens_by_principal.items()})
    changed = provenance.drift(observed, final)
    if changed:
        drifted = DriftError(f"{', '.join(changed)} changed during the run; the run is invalid")
        drifted.output = assemble("invalid", f"drift: {str(drifted)}")
        drifted.output["run"]["drift"] = {"changed": changed, "at_end": final}
        raise drifted
    return assemble("complete")


def check_visibility(dataset: Dataset, listings: dict[str, tuple[str, list[dict]]]) -> dict[str, dict]:
    """Compares everything each principal can list with its hand-labelled visible set. This covers documents no query happens to reach.
    Listing too much is a security violation; listing too little means the labels and the system disagree and aborts the run."""
    check: dict[str, dict] = {}
    for principal, (_, chunks) in listings.items():
        visible = dataset.visibility[principal]
        listed = [Result(c["documentKey"], c["versionNo"], c["charStart"], c["charEnd"], 0) for c in chunks]
        missing = sorted(visible - {r.document for r in listed})
        if missing:
            raise VisibilityMismatchError(
                f"{principal} cannot list {', '.join(missing)}, which visibility.yaml says it may see; "
                "check the labels, or reload the corpus on a fresh stack"
            )
        check[principal] = {
            "visible_documents": len(visible),
            "listed_chunks": len(listed),
            "violations": metrics.violations(listed, visible, dataset.current_version),
        }
    return check


def _record(case, strategy: str, results: list[Result], spans, visible: set[str], current_version: dict[str, int]) -> dict:
    record = {
        "case": case.id,
        "split": case.split,
        "strategy": strategy,
        "tags": case.tags,
        "must_abstain": case.must_abstain,
        "scoped": bool(case.out_of_scope_documents or case.as_of or case.region),
        "violations": metrics.violations(results, visible, current_version),
        "scope_failures": sorted({r.document for r in results if r.document in case.out_of_scope_documents}),
        "results": [[r.document, r.version, r.start, r.end, r.rank] for r in results],
    }
    if spans:
        record |= {
            "recall@5": metrics.recall_at(5, results, spans),
            "recall@10": metrics.recall_at(10, results, spans),
            "mrr@10": metrics.reciprocal_rank(results, spans),
            "ec@10": metrics.evidence_coverage_at(10, results, spans),
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
            "latency_ms": _latency([r["latency_ms"] for r in rows if "latency_ms" in r]),
            "scope_cases": sum(1 for r in rows if r["scoped"]),
            "scope_failures": sum(len(r["scope_failures"]) for r in rows),
        }
    return summary


def _latency(samples: list[float]) -> dict | None:
    """Client-side request latency, HTTP round trip included. It describes the machine the run was made on, not the system in general."""
    if not samples:
        return None
    ordered = sorted(samples)
    return {"p50": ordered[len(ordered) // 2], "p95": ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))], "requests": len(ordered)}


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
        f"policy {', '.join(run_info['policy_versions'])}, chunker {', '.join(run_info.get('chunker_versions', ['not recorded']))}, "
        f"generated {run_info['created_at']}. "
        f"Intervals are 95% percentile bootstraps over cases ({run_info['bootstrap']['samples']:,} samples, seed {run_info['bootstrap']['seed']}).",
        "",
    ]
    manifest = run_info.get("manifest")
    if manifest:
        lines += [_measured(manifest), ""]
    plans = run_info.get("plans", {})
    if plans:
        lines += ["Retrieval plans: " + "; ".join(f"`{s}` `{', '.join(sorted(hashes))}`" for s, hashes in plans.items()) + ".", ""]
    repeated = run_info.get("repeated_degraded_requests", 0)
    if repeated:
        lines += [
            f"{repeated} request(s) were answered degraded and sent again; only complete answers are scored. "
            "This happens when a model call exceeds its timeout on a busy machine.",
            "",
        ]
    if run_info.get("status") == "invalid":
        lines += [f"**This run is invalid and incomplete:** {run_info.get('invalid_reason')}. It is kept as evidence, not as a result.", ""]
    if REFERENCE in strategies:
        lines += [
            f"`{REFERENCE}` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks"
            + (" with their heading paths, as the keyword channel indexes them, " if True in run_info.get("reference_indexes_headings", []) else ", ")
            + "to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.",
            "",
        ]
    for split in run_info["splits"]:
        summary = run_info["summary"][split]
        title = "Results (test split)" if split == "test" else "Tuning split (dev) — not a result"
        lines += [f"## {title}", "", "| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |", "|---|---|---|---|---|---|---|"]
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
            "| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔEC@10 |",
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
    timed = {name: s["latency_ms"] for name, s in test.items() if s.get("latency_ms")}
    if timed:
        lines += [
            "## Latency (test split)",
            "",
            f"Request latency seen by the evaluation client on `{run_info.get('cpu', 'an unrecorded CPU')}`, HTTP round trip included. "
            "It shows what each stage costs on this machine and is not a performance claim.",
            "",
            "| Strategy | Requests | p50 ms | p95 ms |",
            "|---|---|---|---|",
        ]
        lines += [f"| `{name}` | {t['requests']} | {t['p50']:.0f} | {t['p95']:.0f} |" for name, t in timed.items()]
        lines.append("")
    if any(s.get("scope_cases") for s in test.values()):
        lines += [
            "## Scope (test split)",
            "",
            "Cases that set a region or a date, or name documents that are readable but do not apply. Returning such a document is a scope "
            "failure. It is counted here and never as a security violation.",
            "",
            "| Strategy | Scope cases | Scope failures |",
            "|---|---|---|",
        ]
        lines += [f"| `{name}` | {s['scope_cases']} | {s['scope_failures']} |" for name, s in test.items()]
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
    if run_info.get("ranking_check"):
        lines += ["", rankings.render(run_info["ranking_check"]).rstrip()]
    misses = [r for r in records if r.get("recall@10", 1.0) < 1.0 or r["violations"]]
    lines += ["", "## Misses and violations", ""]
    lines += [
        f"- `{r['split']}` · `{r['strategy']}` · `{r['case']}` · recall@10={r.get('recall@10', 'n/a')} · violations={r['violations'] or 'none'}"
        for r in misses
    ]
    if not misses:
        lines.append("None.")
    return "\n".join(lines) + "\n"


def _measured(manifest: dict) -> str:
    """One paragraph naming the system the run measured, from its manifest; a value the system did not report is printed as such."""
    server = manifest["server"]
    profile = server.get("indexProfile")
    index = (
        f"index `{profile['embeddingModel']}@{profile['embeddingRevision']}`, embedding context {'on' if profile['embeddingContext'] else 'off'}, "
        f"chunker release {profile['chunkerRelease']} ({profile['chunkMaxWords']}/{profile['chunkOverlapWords']} words)"
        if profile
        else "index profile not reported"
    )
    reranker = server["reranker"]
    evaluator = manifest["evaluator"]
    return (
        f"Measured: server commit `{server['build']['commit']}` ({server['build']['version']}), {index}, "
        f"reranker `{reranker['model']}@{reranker.get('revision') or 'unreported'}`, server CPU `{server['host']['cpu']}` "
        f"({server['host']['processors']} processors), "
        f"corpus `{manifest['corpus']['digest'][:12]}` ({manifest['corpus']['chunks']} chunks as listed). "
        f"Evaluator commit `{evaluator['git_sha']}`{' with uncommitted changes' if evaluator['uncommitted_changes'] else ''}, "
        f"{manifest['run']['warmup_requests']} warmup requests, up to {manifest['run']['attempt_limit']} attempts per request"
        + (f", metrics version {manifest['run']['metrics_version']}." if "metrics_version" in manifest["run"] else ".")
    )


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
