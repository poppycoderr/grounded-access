# Benchmarks

Committed evaluation runs. Each directory is one run and holds exactly what the run produced:

| File | Contents |
|---|---|
| `run.json` | Dataset version, git commit, strategies, `k`, policy version, platform, date, summary metrics |
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

Runs on the fictional demo corpus are labelled demo benchmarks. They show that the method is reproducible; they say nothing about production retrieval quality.

`results/` at the repository root is the scratch directory for ad-hoc runs and is not tracked.
