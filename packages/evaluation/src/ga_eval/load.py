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
from datetime import UTC, datetime
from pathlib import Path

import httpx

from ga_eval import metrics, tokens
from ga_eval.client import ApiClient
from ga_eval.dataset import Case, Dataset
from ga_eval.metrics import Result
from ga_eval.runner import _git_sha, cpu_model


def run(dataset: Dataset, client: ApiClient, strategies: list[str], workers: int, requests: int, k: int) -> dict:
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
    statuses = Counter(str(r["status"]) if r["status"] else r.get("error", "error") for r in records)
    return {
        "requests": len(records),
        "statuses": dict(sorted(statuses.items())),
        "degraded": dict(sorted(Counter(reason for r in records for reason in r["degraded"]).items())),
        "violations": sum(len(r["violations"]) for r in records),
        "p50_ms": percentile(ordered, 0.50),
        "p95_ms": percentile(ordered, 0.95),
        "max_ms": ordered[-1] if ordered else None,
    }


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
