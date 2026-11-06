"""A load smoke test: the evaluation cases sent concurrently, as their own principals.

It answers three questions a single-request benchmark cannot: does the stack stay correct when requests of different principals overlap,
does it survive more concurrent requests than it has database connections, and where does latency go when a stage is saturated. It is not
a performance benchmark: the corpus is tiny and the numbers describe one machine.
"""

import itertools
import json
import platform
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path

import httpx
import yaml

from ga_eval import metrics, tokens
from ga_eval.client import ApiClient
from ga_eval.dataset import Case, Dataset, load_manifests
from ga_eval.metrics import Result
from ga_eval.provenance import cpu_model
from ga_eval.runner import _git_sha


def with_distractors(dataset: Dataset, directory: Path) -> Dataset:
    """The dataset with generated distractors (`ga-eval distractors`) added to each principal's visible set as their own visibility.yaml
    states it, so a load run on a stack that has them loaded checks them like every other document. Cases are unchanged."""
    labelled = yaml.safe_load((directory / "visibility.yaml").read_text())["principals"]
    visibility = {p: dataset.visibility.get(p, set()) | set(labelled.get(p, [])) for p in dataset.visibility.keys() | labelled.keys()}
    distractor_keys = {d.key for m in load_manifests(directory) for d in m.documents}
    return replace(dataset, visibility=visibility, current_version=dataset.current_version | dict.fromkeys(distractor_keys, 1))


def as_principal(dataset: Dataset, principal: str) -> Dataset:
    """Every case asked by one principal, for measurements that compare two configurations and need no labels, such as the recall of an
    approximate index against exact search. Results are still checked against that principal's visible set."""
    if principal not in dataset.visibility:
        raise ValueError(f"{principal} has no visibility labels")
    return replace(dataset, cases=[c.model_copy(update={"principal": principal}) for c in dataset.cases])


def run(dataset: Dataset, client: ApiClient, strategies: list[str], workers: int, requests: int, k: int, record_results: bool = False) -> dict:
    tokens_by_principal = {
        p: tokens.mint(dataset.root, p, dataset.principals[p], scope="query debug") for p in sorted({c.principal for c in dataset.cases})
    }
    work = list(itertools.islice(itertools.cycle(itertools.product(dataset.cases, strategies)), requests))

    def send(item: tuple[Case, str]) -> dict:
        case, strategy = item
        as_of = case.as_of.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ") if case.as_of else None
        record = {"case": case.id, "principal": case.principal, "strategy": strategy, "status": 0, "degraded": [], "violations": [], "trace_id": None}
        started = time.perf_counter()
        try:
            response = client.search(tokens_by_principal[case.principal], case.query, strategy, k, as_of, case.region)
        except httpx.HTTPStatusError as refused:
            record["status"] = refused.response.status_code
            record["code"] = _code(refused.response)
        except httpx.HTTPError as failed:
            record["error"] = type(failed).__name__
        else:
            results = [Result(r["documentKey"], r["versionNo"], r["charStart"], r["charEnd"], r["rank"]) for r in response["results"]]
            record |= {
                "status": 200,
                "degraded": response["degraded"],
                "violations": metrics.violations(results, dataset.visibility[case.principal], dataset.current_version),
                "trace_id": response["traceId"],
            }
            if record_results:
                # Document, version and offset identify a chunk across two runs on the same stack; the score says how close it was
                record["results"] = [[r.document, r.version, r.start, item["score"]] for r, item in zip(results, response["results"], strict=True)]
        record["latency_ms"] = round((time.perf_counter() - started) * 1000, 1)
        return record

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        records = list(pool.map(send, work))
    seconds = time.perf_counter() - started
    traces = [r["trace_id"] for r in records if r["trace_id"]]
    return {
        "run": {
            "dataset_version": dataset.version,
            "git_sha": _git_sha(dataset.root),
            "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "platform": platform.platform(),
            "cpu": cpu_model(),
            "workers": workers,
            "requests": len(records),
            "principals": len(tokens_by_principal),
            "k": k,
            "seconds": round(seconds, 2),
            "requests_per_second": round(len(records) / seconds, 1),
            "failed": sum(1 for r in records if r["status"] != 200),
            "degraded": sum(1 for r in records if r["degraded"]),
            "security_violations": sum(len(r["violations"]) for r in records),
            "repeated_trace_ids": len(traces) - len(set(traces)),
            "strategies": {s: summarize([r for r in records if r["strategy"] == s]) for s in strategies},
        },
        "requests": records,
    }


def summarize(records: list[dict]) -> dict:
    ordered = sorted(r["latency_ms"] for r in records)
    statuses = Counter(
        (f"{r['status']} {r['code']}" if r.get("code") else str(r["status"])) if r["status"] else r.get("error", "error") for r in records
    )
    return {
        "requests": len(records),
        "statuses": dict(sorted(statuses.items())),
        "degraded": dict(sorted(Counter(reason for r in records for reason in r["degraded"]).items())),
        "violations": sum(len(r["violations"]) for r in records),
        "p50_ms": percentile(ordered, 0.50),
        "p95_ms": percentile(ordered, 0.95),
        "max_ms": ordered[-1] if ordered else None,
    }


def _code(response: httpx.Response) -> str | None:
    """The error code of a refusal (`DATABASE_TIMEOUT`, `BUSY`, ...), which says which limit refused it; never its message."""
    try:
        code = response.json().get("code")
    except ValueError:
        return None
    return code if isinstance(code, str) else None


def percentile(ordered: list[float], share: float) -> float | None:
    return ordered[min(len(ordered) - 1, int(len(ordered) * share))] if ordered else None


def render(output: dict) -> str:
    info = output["run"]
    lines = [
        f"# Load smoke test — dataset {info['dataset_version']}",
        "",
        f"{info['requests']} searches from {info['workers']} concurrent clients as {info['principals']} principals, k={info['k']}, "
        f"commit `{info['git_sha']}`, generated {info['created_at']} on `{info['cpu']}`. "
        f"{info['seconds']} s in total, {info['requests_per_second']} requests per second.",
        "",
        "This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay "
        "correct and shows which stage saturates first. It is not a performance claim.",
        "",
        "| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for strategy, s in info["strategies"].items():
        failed = ", ".join(f"{count} × {status}" for status, count in s["statuses"].items() if status != "200") or "0"
        degraded = ", ".join(f"{count} × {reason}" for reason, count in s["degraded"].items()) or "0"
        lines.append(
            f"| `{strategy}` | {s['requests']} | {failed} | {degraded} | {s['violations']} | {s['p50_ms']} | {s['p95_ms']} | {s['max_ms']} |"
        )
    lines += [
        "",
        f"Every result was checked against the visible set of the principal that asked: {info['security_violations']} unauthorized results. "
        f"Trace ids shared by two requests: {info['repeated_trace_ids']}.",
        "",
    ]
    return "\n".join(lines)


def write(output: dict, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "run.json").write_text(json.dumps(output["run"], indent=2) + "\n")
    (out_dir / "requests.jsonl").write_text("".join(json.dumps(r) + "\n" for r in output["requests"]))
    (out_dir / "report.md").write_text(render(output))
