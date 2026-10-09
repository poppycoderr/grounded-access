"""`ga-eval` command line: validate the dataset, load the demo corpus, run retrieval evaluation, mint demo tokens."""

import argparse
import json
import os
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import answers, between, demo, load, runner, tokens
from ga_eval import dataset as ds
from ga_eval.client import ApiClient, IngestionFailedError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ga-eval")
    parser.add_argument("--data", type=Path, default=_default_data_dir(), help="data directory (default: <repo>/data)")
    parser.add_argument("--base-url", default=os.getenv("GA_BASE_URL", "http://localhost:8080"))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="check cases, quotes and visibility labels against the corpus")
    commands.add_parser("load", help="ingest every manifest into its tenant")
    answers_parser = commands.add_parser("answers", help="evaluate generated answers; needs a chat model configured in the control plane")
    answers_parser.add_argument("--out", type=Path, help="output directory (default: results/answers-<timestamp>)")
    answers_parser.add_argument("--split", action="append", choices=["dev", "test"], help="repeatable; default: both")
    run_parser = commands.add_parser("run", help="evaluate retrieval strategies and enforce the security gate")
    run_parser.add_argument("--strategy", action="append", choices=[*runner.SYSTEM_STRATEGIES, runner.REFERENCE], help="repeatable; default: all")
    run_parser.add_argument("--split", action="append", choices=["dev", "test"], help="repeatable; default: all")
    run_parser.add_argument("--k", type=int, default=10)
    run_parser.add_argument("--out", type=Path, help="output directory (default: results/<timestamp>)")
    load_parser = commands.add_parser("load-test", help="send the cases concurrently and check every result; a smoke test, not a benchmark")
    load_parser.add_argument("--strategy", action="append", choices=runner.SYSTEM_STRATEGIES, help="repeatable; default: all")
    load_parser.add_argument("--workers", type=int, default=16, help="concurrent clients")
    load_parser.add_argument("--requests", type=int, default=600)
    load_parser.add_argument("--k", type=int, default=10)
    load_parser.add_argument("--allow-degraded", action="store_true", help="report degraded results instead of failing on them")
    load_parser.add_argument("--out", type=Path, help="output directory (default: results/load-<timestamp>)")
    compare_parser = commands.add_parser("compare", help="paired comparison of two runs of the same cases, strategy by strategy")
    compare_parser.add_argument("before", type=Path)
    compare_parser.add_argument("after", type=Path)
    compare_parser.add_argument("--split", choices=["dev", "test"], default="test")
    compare_parser.add_argument("--out", type=Path, help="write comparison.json and comparison.md into this directory")
    demo_parser = commands.add_parser("demo", help="a guided tour: identities, hidden documents, answers, traces")
    demo_parser.add_argument("--pause", action="store_true", help="wait for Enter between the steps")
    search_parser = commands.add_parser("search", help="search as a demo principal and print the ranked results")
    search_parser.add_argument("principal")
    search_parser.add_argument("query")
    search_parser.add_argument("--strategy", choices=runner.SYSTEM_STRATEGIES, default="dense-only")
    search_parser.add_argument("--k", type=int, default=3)
    mint_parser = commands.add_parser("mint-token", help="print a demo token for a principal")
    mint_parser.add_argument("principal")
    mint_parser.add_argument("--scope")
    args = parser.parse_args(argv)

    if args.command == "compare":
        try:
            comparison = between.compare(args.before, args.after, args.split)
        except between.IncomparableRunsError as incomparable:
            print(f"error: {incomparable}", file=sys.stderr)
            return 1
        print(between.render(comparison))
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / "comparison.json").write_text(json.dumps(comparison, indent=2) + "\n")
            (args.out / "comparison.md").write_text(between.render(comparison))
        return 0
    dataset = ds.load(args.data)
    if args.command == "validate":
        return _validate(dataset)
    if args.command == "mint-token":
        print(tokens.mint(dataset.root, args.principal, dataset.principals[args.principal], args.scope))
        return 0
    client = ApiClient(args.base_url)
    client.wait_until_ready()
    if args.command == "answers":
        return _answers(dataset, client, args)
    if args.command == "load":
        return _load(dataset, client)
    if args.command == "demo":
        try:
            demo.Tour(dataset, client, (lambda: input("\n[Enter] ")) if args.pause else (lambda: None)).run()
        except demo.DemoFailedError as failed:
            print(f"error: {failed}", file=sys.stderr)
            return 1
        return 0
    if args.command == "load-test":
        return _load_test(dataset, client, args)
    if args.command == "search":
        return _search(dataset, client, args.principal, args.query, args.strategy, args.k)
    if _validate(dataset) != 0:
        return 1
    try:
        output = runner.run(
            dataset, client, args.strategy or [*runner.SYSTEM_STRATEGIES, runner.REFERENCE], args.k, set(args.split or ["dev", "test"])
        )
    except (runner.MixedChunkerError, runner.ReloadedCorpusError, runner.DegradedRunError, runner.VisibilityMismatchError) as invalid:
        print(f"error: {invalid}", file=sys.stderr)
        return 1
    out_dir = args.out or Path("results") / datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    runner.write(output, out_dir)
    print((out_dir / "report.md").read_text())
    violations = output["run"]["security_violations"]
    if violations:
        print(f"SECURITY GATE FAILED: {violations} unauthorized results", file=sys.stderr)
        return 2
    scope_failures = output["run"]["scope_failures"]
    if scope_failures:
        print(f"SCOPE CHECK FAILED: {scope_failures} results outside the scope of their request", file=sys.stderr)
        return 3
    return 0


def _validate(dataset: ds.Dataset) -> int:
    problems = ds.validate(dataset)
    for problem in problems:
        print(f"invalid: {problem}", file=sys.stderr)
    if not problems:
        print(f"dataset {dataset.version}: {len(dataset.cases)} cases, {len(dataset.texts)} documents, valid")
    bands = ds.overlap_bands(dataset)
    splits = Counter(c.split for c in dataset.cases)
    print("lexical overlap of answerable cases: " + ", ".join(f"{band} {len(ids)}" for band, ids in bands.items()))
    print("splits: " + ", ".join(f"{split} {count}" for split, count in sorted(splits.items())))
    print(f"authorization negatives: {len(ds.authorization_negatives(dataset))}")
    return 1 if problems else 0


def _load(dataset: ds.Dataset, client: ApiClient) -> int:
    for manifest in dataset.manifests:
        admin = f"{manifest.tenant}-admin"
        token = tokens.mint(dataset.root, admin, dataset.principals[admin])
        # Documents with a history are ingested oldest version first, so their version numbers match the labels in the dataset. A document
        # that is already loaded gets only its current version: replaying its history would add versions on every load.
        loaded = _loaded_documents(dataset, client, manifest.tenant)
        pending = {d.key: [d] if d.key in loaded else d.versions() for d in manifest.documents}
        rounds = max(len(versions) for versions in pending.values())
        for number in range(rounds):
            documents = [
                _document(dataset, d, pending[d.key][number - (rounds - len(pending[d.key]))])
                for d in manifest.documents
                if number >= rounds - len(pending[d.key])
            ]
            try:
                job = client.ingest(token, documents)
            except IngestionFailedError as failure:
                print(f"{manifest.tenant}: {failure}", file=sys.stderr)
                return 1
            counts = ", ".join(f"{job[field]} {field}" for field in ("created", "updated", "unchanged", "chunks"))
            print(f"{manifest.tenant}: job {job['jobId']} succeeded after {job['attempts']} attempt(s): {counts}")
    return 0


def _loaded_documents(dataset: ds.Dataset, client: ApiClient, tenant: str) -> set[str]:
    """The documents of a tenant that some demo principal can already list."""
    loaded: set[str] = set()
    for name, claims in dataset.principals.items():
        if claims.get("tenant_id") == tenant and claims.get("scope") != "admin":
            _, chunks = client.list_chunks(tokens.mint(dataset.root, name, claims, scope="query debug"), include_out_of_scope=True)
            loaded |= {chunk["documentKey"] for chunk in chunks}
    return loaded


def _load_test(dataset: ds.Dataset, client: ApiClient, args: argparse.Namespace) -> int:
    output = load.run(dataset, client, args.strategy or list(runner.SYSTEM_STRATEGIES), args.workers, args.requests, args.k)
    out_dir = args.out or Path("results") / f"load-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}"
    load.write(output, out_dir)
    print((out_dir / "report.md").read_text())
    info = output["run"]
    if info["security_violations"] or info["repeated_trace_ids"]:
        print(f"SECURITY GATE FAILED: {info['security_violations']} unauthorized results under load", file=sys.stderr)
        return 2
    if info["failed"]:
        print(f"error: {info['failed']} requests were not answered", file=sys.stderr)
        return 1
    if info["degraded"] and not args.allow_degraded:
        print(f"error: {info['degraded']} results were degraded; pass --allow-degraded to measure saturation instead of failing", file=sys.stderr)
        return 1
    return 0


def _answers(dataset: ds.Dataset, client: ApiClient, args: argparse.Namespace) -> int:
    try:
        output = answers.run(dataset, client, set(args.split or ["dev", "test"]))
    except answers.NoChatModelError as missing:
        print(f"error: {missing}", file=sys.stderr)
        return 1
    out_dir = args.out or Path("results") / ("answers-" + datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ"))
    answers.write(output, out_dir)
    print((out_dir / "answers-report.md").read_text())
    if output["run"]["security_violations"]:
        print(f"SECURITY GATE FAILED: {output['run']['security_violations']} unauthorized evidence items", file=sys.stderr)
        return 2
    return 0


def _document(dataset: ds.Dataset, document: ds.ManifestDocument, version: ds.ManifestVersion) -> dict:
    return {
        "key": document.key,
        "title": document.title,
        "content": (dataset.root / version.file).read_text(encoding="utf-8"),
        "format": _format(version.file),
        "classification": version.classification,
        "allowedDepartments": version.allowed_departments,
        "requiredProjects": version.required_projects,
        "appliesToRegions": version.applies_to_regions,
        "validFrom": _instant(version.valid_from),
        "validTo": _instant(version.valid_to),
    }


def _instant(moment: datetime | None) -> str | None:
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ") if moment else None


def _format(file: str) -> str:
    """Markdown for `.md` files, plain text for everything else."""
    return "markdown" if file.endswith(".md") else "text"


def _search(dataset: ds.Dataset, client: ApiClient, principal: str, query: str, strategy: str, k: int) -> int:
    token = tokens.mint(dataset.root, principal, dataset.principals[principal])
    response = client.search(token, query, strategy, k)
    tenant = dataset.principals[principal]["tenant_id"]
    print(f"{principal} (tenant {tenant}) · {strategy} · policy {response['policyVersion']}")
    for r in response["results"]:
        print(f"  {r['rank']}. {r['documentKey']} › {r['sectionPath']}")
        print(f"     {r['text'].splitlines()[0][:110]}")
    if not response["results"]:
        print("  (no results)")
    return 0


def _default_data_dir() -> Path:
    for directory in [Path.cwd(), *Path.cwd().parents]:
        if (directory / "data" / "principals.yaml").is_file():
            return directory / "data"
    return Path("data")


if __name__ == "__main__":
    sys.exit(main())
