"""`ga-eval` command line: validate the dataset, load the demo corpus, run retrieval evaluation, mint demo tokens."""

import argparse
import os
import sys
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import dataset as ds
from ga_eval import runner, tokens
from ga_eval.client import ApiClient


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ga-eval")
    parser.add_argument("--data", type=Path, default=_default_data_dir(), help="data directory (default: <repo>/data)")
    parser.add_argument("--base-url", default=os.getenv("GA_BASE_URL", "http://localhost:8080"))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="check cases, quotes and visibility labels against the corpus")
    commands.add_parser("load", help="ingest every manifest into its tenant")
    run_parser = commands.add_parser("run", help="evaluate retrieval strategies and enforce the security gate")
    run_parser.add_argument("--strategy", action="append", choices=["sparse-only", "dense-only"], help="repeatable; default: all")
    run_parser.add_argument("--split", action="append", choices=["dev", "test"], help="repeatable; default: all")
    run_parser.add_argument("--k", type=int, default=10)
    run_parser.add_argument("--out", type=Path, help="output directory (default: results/<timestamp>)")
    search_parser = commands.add_parser("search", help="search as a demo principal and print the ranked results")
    search_parser.add_argument("principal")
    search_parser.add_argument("query")
    search_parser.add_argument("--strategy", choices=["sparse-only", "dense-only"], default="dense-only")
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
    output = runner.run(dataset, client, args.strategy or ["sparse-only", "dense-only"], args.k, set(args.split or ["dev", "test"]))
    out_dir = args.out or Path("results") / datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    runner.write(output, out_dir)
    print((out_dir / "report.md").read_text())
    violations = sum(s["security_violations"] for s in output["run"]["summary"].values())
    if violations:
        print(f"SECURITY GATE FAILED: {violations} unauthorized results", file=sys.stderr)
        return 2
    return 0


def _validate(dataset: ds.Dataset) -> int:
    problems = ds.validate(dataset)
    for problem in problems:
        print(f"invalid: {problem}", file=sys.stderr)
    if not problems:
        print(f"dataset {dataset.version}: {len(dataset.cases)} cases, {len(dataset.texts)} documents, valid")
    return 1 if problems else 0


def _load(dataset: ds.Dataset, client: ApiClient) -> int:
    for manifest in dataset.manifests:
        admin = f"{manifest.tenant}-admin"
        token = tokens.mint(dataset.root, admin, dataset.principals[admin])
        documents = [{"key": d.key, "title": d.title, "content": (dataset.root / d.file).read_text(encoding="utf-8")} for d in manifest.documents]
        result = client.ingest(token, documents)
        print(f"{manifest.tenant}: {result}")
    return 0


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
