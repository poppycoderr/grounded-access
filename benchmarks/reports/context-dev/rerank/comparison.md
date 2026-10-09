# `baseline` → `rerank` — dev split, dataset v4

Before: commit `3e8d3d0`, chunker markdown/2. After: commit `3e8d3d0`, chunker markdown/2. Each row is one strategy on the same cases in both runs; the difference is after minus before with a 95% interval, and counts only if the interval excludes zero.

| Strategy | Cases | Recall@10 | ΔRecall@10 | MRR@10 | ΔMRR@10 | Cases whose rank changed |
|---|---|---|---|---|---|---|
| `sparse-only` | 23 | 1.000 → 1.000 | +0.000 [+0.00, +0.00] — no detectable difference | 0.663 → 0.663 | +0.000 [+0.00, +0.00] — no detectable difference | 0 |
| `dense-only` | 23 | 0.957 → 0.957 | +0.000 [+0.00, +0.00] — no detectable difference | 0.848 → 0.848 | +0.000 [+0.00, +0.00] — no detectable difference | 0 |
| `hybrid-rrf` | 23 | 1.000 → 1.000 | +0.000 [+0.00, +0.00] — no detectable difference | 0.813 → 0.813 | +0.000 [+0.00, +0.00] — no detectable difference | 0 |
| `hybrid-rrf-rerank` | 23 | 1.000 → 1.000 | +0.000 [+0.00, +0.00] — no detectable difference | 0.928 → 0.935 | +0.007 [+0.00, +0.02] — no detectable difference | 1 |
| `bm25-reference` | 23 | 0.957 → 0.957 | +0.000 [+0.00, +0.00] — no detectable difference | 0.668 → 0.668 | +0.000 [+0.00, +0.00] — no detectable difference | 0 |
