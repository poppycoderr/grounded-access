# `baseline` → `embedding-keyword-rerank` — dev split, dataset v4

Before: commit `3e8d3d0`, chunker markdown/2. After: commit `3e8d3d0`, chunker markdown/3. Each row is one strategy on the same cases in both runs; the difference is after minus before with a 95% interval, and counts only if the interval excludes zero.

| Strategy | Cases | Recall@10 | ΔRecall@10 | MRR@10 | ΔMRR@10 | Cases whose rank changed |
|---|---|---|---|---|---|---|
| `sparse-only` | 23 | 1.000 → 1.000 | +0.000 [+0.00, +0.00] — no detectable difference | 0.663 → 0.704 | +0.041 [+0.00, +0.09] — better | 5 |
| `dense-only` | 23 | 0.957 → 0.957 | +0.000 [+0.00, +0.00] — no detectable difference | 0.848 → 0.906 | +0.058 [+0.00, +0.14] — no detectable difference | 3 |
| `hybrid-rrf` | 23 | 1.000 → 1.000 | +0.000 [+0.00, +0.00] — no detectable difference | 0.813 → 0.882 | +0.069 [+0.01, +0.15] — better | 5 |
| `hybrid-rrf-rerank` | 23 | 1.000 → 1.000 | +0.000 [+0.00, +0.00] — no detectable difference | 0.928 → 0.900 | -0.028 [-0.10, +0.02] — no detectable difference | 2 |
| `bm25-reference` | 23 | 0.957 → 1.000 | +0.043 [+0.00, +0.13] — no detectable difference | 0.668 → 0.747 | +0.079 [+0.01, +0.16] — better | 9 |
