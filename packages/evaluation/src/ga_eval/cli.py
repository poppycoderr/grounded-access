"""`ga-eval` command line: validate the dataset, load the demo corpus, run retrieval evaluation, mint demo tokens."""

import argparse
import os
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import dataset as ds
from ga_eval import runner, tokens
from ga_eval.client import ApiClient, IngestionFailedError


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ga-eval")
    parser.add_argument("--data", type=Path, default=_default_data_dir(), help="data directory (default: <repo>/data)")
    parser.add_argument("--base-url", default=os.getenv("GA_BASE_URL", "http://localhost:8080"))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="check cases, quotes and visibility labels against the corpus")
    commands.add_parser("load", help="ingest every manifest into its tenant")
    run_parser = commands.add_parser("run", help="evaluate retrieval strategies and enforce the security gate")
    run_parser.add_argument("--strategy", action="append", choices=[*runner.SYSTEM_STRATEGIES, runner.REFERENCE], help="repeatable; default: all")
    run_parser.add_argument("--split", action="append", choices=["dev", "test"], help="repeatable; default: all")
    run_parser.add_argument("--k", type=int, default=10)
    run_parser.add_argument("--out", type=Path, help="output directory (default: results/<timestamp>)")
    search_parser = commands.add_parser("search", help="search as a demo principal and print the ranked results")
    search_parser.add_argument("principal")
    search_parser.add_argument("query")
    search_parser.add_argument("--strategy", choices=runner.SYSTEM_STRATEGIES, default="dense-only")
    search_parser.add_argument("--k", type=int, default=3)
    mint_parser = commands.add_parser("mint-token", help="print a demo token for a principal")
    mint_parser.add_argument("principal")
    mint_parser.add_argument("--scope")
    args = parser.parse_args(argv)

    dataset = ds.load(args.data)
    if args.command == "validate":
        return _validate(dataset)
    if args.command == "mint-token":
        print(tokens.mint(dataset.root, args.principal, dataset.principals[args.principal], args.scope))
        return 0
    client = ApiClient(args.base_url)
    client.wait_until_ready()
    if args.command == "load":
        return _load(dataset, client)
    if args.command == "search":
        return _search(dataset, client, args.principal, args.query, args.strategy, args.k)
    if _validate(dataset) != 0:
        return 1
    try:
        output = runner.run(
            dataset, client, args.strategy or [*runner.SYSTEM_STRATEGIES, runner.REFERENCE], args.k, set(args.split or ["dev", "test"])
        )
    except (runner.MixedChunkerError, runner.DegradedRunError, runner.VisibilityMismatchError) as invalid:
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
        rounds = max(len(d.versions()) for d in manifest.documents)
        for number in range(rounds):
            # Documents with a history are ingested oldest version first, so their version numbers match the labels in the dataset.
            documents = [
                _document(dataset, d, d.versions()[number - (rounds - len(d.versions()))])
                for d in manifest.documents
                if number >= rounds - len(d.versions())
            ]
            try:
                job = client.ingest(token, documents)
            except IngestionFailedError as failure:
                print(f"{manifest.tenant}: {failure}", file=sys.stderr)
                return 1
            counts = ", ".join(f"{job[field]} {field}" for field in ("created", "updated", "unchanged", "chunks"))
            print(f"{manifest.tenant}: job {job['jobId']} succeeded after {job['attempts']} attempt(s): {counts}")
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
