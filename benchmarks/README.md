# Benchmarks

Committed evaluation runs. Each directory is one run and holds exactly what the run produced:

| File | Contents |
|---|---|
| `run.json` | Dataset version, git commit, strategies, `k`, policy and chunker versions, retrieval plans and their hashes, platform, CPU model, date, summary metrics |
| `cases.jsonl` | Per case and per strategy: ranked results, metrics, security violations |
| `report.md` | Rendered from the two files above, never edited by hand |

Numbers quoted in the README or in articles must link to a directory here. Regenerate a run against a locally running stack:

```bash
docker compose up -d --build --wait
./scripts/load-demo
./scripts/benchmark --out benchmarks/reports/<name>
```

## Which machine produces the published numbers

Published runs come from the CI `smoke` job on a GitHub-hosted Linux x86_64 runner, because anyone can re-run that environment. The job uploads the run as the `evaluation-results` artifact; a published directory here is that artifact, unchanged. On pull requests the recorded commit is the merge commit GitHub builds, so it can differ from the commit that lands on `main` even though the code is the same.

Keyword (`sparse-only`) and `bm25-reference` rankings are bit-identical on every machine. Dense rankings are not guaranteed to be: ONNX Runtime uses different vector kernels on arm64 and x86_64, so candidates whose cosine scores differ in the last digits can swap places. For `m1a-baseline` that changed ten ranking lists below the first relevant result and one metric: `product-sso-059` has its evidence at rank 2 on an Apple Silicon Mac and at rank 3 on the CI runner, moving dense MRR@10 from 0.864 to 0.860. A local run that differs from a published one by that kind of margin is expected; a larger difference is a bug.

The same effect, smaller, appears between hosted x86_64 runners, which do not all have the same CPU. A CI run on dataset v2 differed from `m2-labelled-dataset` in 2 of 432 ranked lists: in each, two adjacent dense candidates with near-equal scores swapped places between ranks 7 and 10, and no metric changed. Published numbers are therefore reproducible to the reported precision, and dense result lists are reproducible up to the order of near-ties. From this change on, `run.json` records the CPU model.

`m1b-hybrid` adds the `hybrid-rrf` strategy to the same cases; [docs/evaluation/m1b-hybrid-analysis.md](../docs/evaluation/m1b-hybrid-analysis.md) reads its result. Its single-channel and BM25 rankings are identical to `m1a-baseline`.

`m0-walking-skeleton`, `m1a-baseline` and `m1b-hybrid` use dataset v1. `m2-labelled-dataset` is the report on dataset v2; `m2-authorization` and `m3-rerank` are reports on dataset v3, the second adding the `hybrid-rrf-rerank` strategy and per-strategy latency; v2 adds labelled documents and authorization cases, and v3 adds documents with regions and validity windows and scope cases; numbers across the two versions are not comparable (see the [dataset card](../data/eval/DATASET_CARD.md)). One v1 finding did not carry over: BM25 was measurably ahead of PostgreSQL FTS on v1 (MRR@10 +0.10 [+0.02, +0.18]) and shows no detectable difference on v2 (+0.05 [−0.02, +0.12]).

Runs on the fictional demo corpus are labelled demo benchmarks. They show that the method is reproducible; they say nothing about production retrieval quality.

`results/` at the repository root is the scratch directory for ad-hoc runs and is not tracked.
