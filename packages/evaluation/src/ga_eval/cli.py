"""`ga-eval` command line: validate the dataset, load the demo corpus, run retrieval evaluation, mint demo tokens."""

import argparse
import json
import os
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from ga_eval import ann, answers, between, demo, distractors, load, rankings, runner, scale, tokens
from ga_eval import dataset as ds
from ga_eval.client import ApiClient, IngestionFailedError

# Documents per ingestion job; the control plane accepts at most 500
LOAD_BATCH = 200

# Documents per ingestion job; the control plane accepts at most 500
LOAD_BATCH = 200


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ga-eval")
    parser.add_argument("--data", type=Path, default=_default_data_dir(), help="data directory (default: <repo>/data)")
    parser.add_argument("--base-url", default=os.getenv("GA_BASE_URL", "http://localhost:8080"))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("validate", help="check cases, quotes and visibility labels against the corpus")
    load_corpus_parser = commands.add_parser("load", help="ingest every manifest into its tenant")
    load_corpus_parser.add_argument(
        "--manifests", type=Path, help="load the manifests and corpus of this directory instead, for example generated distractors"
    )
    ann_parser = commands.add_parser("ann-recall", help="recall of an approximate index against exact search, from two recorded load tests")
    ann_parser.add_argument("exact", type=Path)
    ann_parser.add_argument("candidate", type=Path)
    ann_parser.add_argument("--k", type=int, default=10)
    ann_parser.add_argument("--out", type=Path, help="write ann-recall.json and ann-recall.md into this directory")
    scale_parser = commands.add_parser("scale-report", help="render summary.json and summary.md of a scripts/scale-run directory")
    scale_parser.add_argument("directory", type=Path)
    distractors_parser = commands.add_parser("distractors", help="write generated, labelled distractor documents for scale and load runs")
    distractors_parser.add_argument("--seed", type=int, default=1)
    distractors_parser.add_argument("--documents", type=int, default=2000)
    distractors_parser.add_argument("--out", type=Path, required=True)
    answers_parser = commands.add_parser("answers", help="evaluate generated answers; needs a chat model configured in the control plane")
    answers_parser.add_argument("--out", type=Path, help="output directory (default: results/answers-<timestamp>)")
    answers_parser.add_argument("--split", action="append", choices=["dev", "test"], help="repeatable; default: both")
    run_parser = commands.add_parser("run", help="evaluate retrieval strategies and enforce the security gate")
    run_parser.add_argument("--strategy", action="append", choices=[*runner.SYSTEM_STRATEGIES, runner.REFERENCE], help="repeatable; default: all")
    run_parser.add_argument("--split", action="append", choices=["dev", "test"], help="repeatable; default: all")
    run_parser.add_argument("--k", type=int, default=10)
    run_parser.add_argument("--out", type=Path, help="output directory (default: results/<timestamp>)")
    run_parser.add_argument(
        "--expected-rankings", type=Path, help="keyword rankings a run must reproduce (default: <repo>/benchmarks/expected/keyword-rankings.json)"
    )
    rankings_parser = commands.add_parser("rankings", help="write the expected keyword rankings from a complete run")
    rankings_parser.add_argument("run_dir", type=Path)
    rankings_parser.add_argument("--out", type=Path, help="default: <repo>/benchmarks/expected/keyword-rankings.json")
    load_parser = commands.add_parser("load-test", help="send the cases concurrently and check every result; a smoke test, not a benchmark")
    load_parser.add_argument("--strategy", action="append", choices=runner.SYSTEM_STRATEGIES, help="repeatable; default: all")
    load_parser.add_argument("--workers", type=int, default=16, help="concurrent clients")
    load_parser.add_argument("--requests", type=int, default=600)
    load_parser.add_argument("--k", type=int, default=10)
    load_parser.add_argument("--allow-degraded", action="store_true", help="report degraded results instead of failing on them")
    load_parser.add_argument("--out", type=Path, help="output directory (default: results/load-<timestamp>)")
    load_parser.add_argument("--as-principal", help="send every case as this principal instead of its own")
    load_parser.add_argument("--record-results", action="store_true", help="keep each request's ranked results, for ga-eval ann-recall")
    load_parser.add_argument(
        "--distractors", type=Path, help="a directory written by `ga-eval distractors` and loaded into the stack; its documents are checked too"
    )
    compare_parser = commands.add_parser("compare", help="paired comparison of two runs of the same cases, strategy by strategy")
    compare_parser.add_argument("before", type=Path)
    compare_parser.add_argument("after", type=Path)
    compare_parser.add_argument("--split", choices=["dev", "test"], default="test")
    compare_parser.add_argument("--out", type=Path, help="write comparison.json and comparison.md into this directory")
    compare_parser.add_argument(
        "--allow-subset", action="store_true", help="compare only the cases both runs have, list the others, and mark the result as a subset"
    )
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

    if args.command == "ann-recall":
        try:
            comparison = ann.compare(args.exact, args.candidate, args.k)
        except ann.MismatchedRunsError as mismatch:
            print(f"error: {mismatch}", file=sys.stderr)
            return 1
        report = ann.render(comparison, args.exact.name, args.candidate.name)
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / "ann-recall.json").write_text(json.dumps(comparison, indent=2) + "\n")
            (args.out / "ann-recall.md").write_text(report)
        print(report)
        return 0
    if args.command == "scale-report":
        summary = scale.summarize(args.directory)
        (args.directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        (args.directory / "summary.md").write_text(scale.render(summary))
        print((args.directory / "summary.md").read_text())
        return 0
    if args.command == "rankings":
        run_info, records = between.load(args.run_dir)
        try:
            expected = rankings.expected_from(run_info, records)
        except rankings.RankingsUnavailableError as unavailable:
            print(f"error: {unavailable}", file=sys.stderr)
            return 1
        destination = args.out or _expected_rankings(args.data)
        rankings.write(expected, destination)
        print(f"expected rankings of {', '.join(expected['strategies'])} written to {destination}")
        return 0
    if args.command == "compare":
        try:
            comparison = between.compare(args.before, args.after, args.split, allow_subset=args.allow_subset)
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
        if args.manifests:
            return _load(dataset, client, ds.load_manifests(args.manifests), args.manifests)
        return _load(dataset, client, dataset.manifests, dataset.root)
    if args.command == "distractors":
        distractors.write(distractors.generate(args.seed, args.documents, dataset.principals), args.out)
        print(f"wrote {args.documents} distractor documents (seed {args.seed}) to {args.out}")
        return 0
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
    out_dir = args.out or Path("results") / datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    try:
        output = runner.run(
            dataset, client, args.strategy or [*runner.SYSTEM_STRATEGIES, runner.REFERENCE], args.k, set(args.split or ["dev", "test"])
        )
    except runner.InvalidRunError as invalid:
        # What was measured before the run became invalid is kept: it may hold the evidence of what went wrong.
        print(f"error: {invalid}", file=sys.stderr)
        if invalid.output is None:
            return 1
        runner.write(invalid.output, out_dir)
        print(f"partial results of the invalid run: {out_dir}", file=sys.stderr)
        return _gate_exit(invalid.output["run"], 1)
    except (runner.MixedChunkerError, runner.ReloadedCorpusError, runner.VisibilityMismatchError) as invalid:
        print(f"error: {invalid}", file=sys.stderr)
        return 1
    expected = args.expected_rankings or _expected_rankings(args.data)
    output["run"]["ranking_check"] = (
        rankings.check(output["run"], output["cases"], rankings.load(expected))
        if expected.is_file()
        else {"status": "not applicable", "reason": f"there is no {expected.name}", "differences": []}
    )
    runner.write(output, out_dir)
    print((out_dir / "report.md").read_text())
    gate = _gate_exit(output["run"], 0)
    if gate == 0 and output["run"]["ranking_check"]["status"] == "failed":
        print(f"RANKING CHECK FAILED: {output['run']['ranking_check']['reason']}", file=sys.stderr)
        return 4
    return gate


def _expected_rankings(data: Path) -> Path:
    return data.resolve().parent / "benchmarks" / "expected" / "keyword-rankings.json"


def _gate_exit(run: dict, otherwise: int) -> int:
    """The exit code of a run that produced results: 2 for any security violation, 3 for any scope failure, `otherwise` if neither."""
    violations = run["security_violations"]
    if violations:
        print(f"SECURITY GATE FAILED: {violations} unauthorized results", file=sys.stderr)
        return 2
    scope_failures = run["scope_failures"]
    if scope_failures:
        print(f"SCOPE CHECK FAILED: {scope_failures} results outside the scope of their request", file=sys.stderr)
        return 3
    return otherwise


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


def _load(dataset: ds.Dataset, client: ApiClient, manifests: list[ds.Manifest], files: Path) -> int:
    for manifest in manifests:
        admin = f"{manifest.tenant}-admin"
        token = tokens.mint(dataset.root, admin, dataset.principals[admin])
        # Documents with a history are ingested oldest version first, so their version numbers match the labels in the dataset. A document
        # that is already loaded gets only its current version: replaying its history would add versions on every load.
        loaded = _loaded_documents(dataset, client, manifest.tenant)
        pending = {d.key: [d] if d.key in loaded else d.versions() for d in manifest.documents}
        rounds = max(len(versions) for versions in pending.values())
        for number in range(rounds):
            documents = [
                _document(files, d, pending[d.key][number - (rounds - len(pending[d.key]))])
                for d in manifest.documents
                if number >= rounds - len(pending[d.key])
            ]
            # One job takes at most 500 documents; the demo corpus fits in one
            for start in range(0, len(documents), LOAD_BATCH):
                try:
                    job = client.ingest(token, documents[start : start + LOAD_BATCH])
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
    if args.distractors:
        dataset = load.with_distractors(dataset, args.distractors)
    if args.as_principal:
        dataset = load.as_principal(dataset, args.as_principal)
    output = load.run(dataset, client, args.strategy or list(runner.SYSTEM_STRATEGIES), args.workers, args.requests, args.k, args.record_results)
    output["run"]["distractor_documents"] = sum(len(m.documents) for m in ds.load_manifests(args.distractors)) if args.distractors else 0
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
    out_dir = args.out or Path("results") / ("answers-" + datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ"))
    try:
        output = answers.run(dataset, client, set(args.split or ["dev", "test"]))
    except answers.NoChatModelError as missing:
        print(f"error: {missing}", file=sys.stderr)
        return 1
    except runner.InvalidRunError as invalid:
        print(f"error: {invalid}", file=sys.stderr)
        if invalid.output is None:
            return 1
        answers.write(invalid.output, out_dir)
        print(f"results of the invalid run: {out_dir}", file=sys.stderr)
        return _gate_exit(invalid.output["run"], 1)
    answers.write(output, out_dir)
    print((out_dir / "answers-report.md").read_text())
    return _gate_exit(output["run"], 0)


def _document(files: Path, document: ds.ManifestDocument, version: ds.ManifestVersion) -> dict:
    return {
        "key": document.key,
        "title": document.title,
        "content": (files / version.file).read_text(encoding="utf-8"),
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
