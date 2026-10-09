# `v0.1` → `heading-context` — test split, dataset v4

Before: commit `ea137fb`, chunker markdown/2. After: commit `29b5bab`, chunker markdown/3. Each row is one strategy on the same cases in both runs; the difference is after minus before with a 95% interval, and counts only if the interval excludes zero.

| Strategy | Cases | Recall@10 | ΔRecall@10 | MRR@10 | ΔMRR@10 | Cases whose rank changed |
|---|---|---|---|---|---|---|
| `sparse-only` | 70 | 0.929 → 0.943 | +0.014 [-0.04, +0.07] — no detectable difference | 0.690 → 0.740 | +0.050 [-0.01, +0.11] — no detectable difference | 22 |
| `dense-only` | 70 | 0.971 → 0.979 | +0.007 [-0.02, +0.04] — no detectable difference | 0.865 → 0.895 | +0.030 [-0.03, +0.09] — no detectable difference | 14 |
| `hybrid-rrf` | 70 | 0.971 → 0.986 | +0.014 [+0.00, +0.04] — no detectable difference | 0.825 → 0.867 | +0.042 [-0.01, +0.10] — no detectable difference | 13 |
| `hybrid-rrf-rerank` | 70 | 0.971 → 0.986 | +0.014 [+0.00, +0.04] — no detectable difference | 0.957 → 0.971 | +0.014 [+0.00, +0.04] — no detectable difference | 1 |
| `bm25-reference` | 70 | 0.921 → 0.943 | +0.021 [-0.03, +0.07] — no detectable difference | 0.741 → 0.772 | +0.030 [-0.02, +0.08] — no detectable difference | 17 |
