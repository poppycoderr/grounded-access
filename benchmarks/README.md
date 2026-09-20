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

Runs on the fictional demo corpus are labelled demo benchmarks. They show that the method is reproducible; they say nothing about production retrieval quality.

`results/` at the repository root is the scratch directory for ad-hoc runs and is not tracked.
