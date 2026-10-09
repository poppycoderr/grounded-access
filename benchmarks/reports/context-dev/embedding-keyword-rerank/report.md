# Retrieval evaluation — dataset v4

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `3e8d3d0`, 34 cases, k=10, policy abac/1, chunker markdown/3, generated 2026-10-09T17:17:16+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Retrieval plans: `sparse-only` `d7967398b8117b5f`; `dense-only` `d489d865e16bcda9`; `hybrid-rrf` `2598002ea9c41e8d`; `hybrid-rrf-rerank` `1e58342ebda973f9`.

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks with their heading paths, as the keyword channel indexes them, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 23 | 0.957 [0.87, 1.00] | 1.000 [1.00, 1.00] | 0.704 [0.56, 0.84] | 0.777 [0.67, 0.88] | 0 |
| `dense-only` | 23 | 0.957 [0.87, 1.00] | 0.957 [0.87, 1.00] | 0.906 [0.79, 1.00] | 0.919 [0.81, 1.00] | 0 |
| `hybrid-rrf` | 23 | 0.957 [0.87, 1.00] | 1.000 [1.00, 1.00] | 0.882 [0.76, 0.98] | 0.911 [0.82, 0.98] | 0 |
| `hybrid-rrf-rerank` | 23 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.900 [0.80, 0.98] | 0.925 [0.85, 0.98] | 0 |
| `bm25-reference` | 23 | 0.870 [0.74, 1.00] | 1.000 [1.00, 1.00] | 0.747 [0.60, 0.88] | 0.808 [0.70, 0.91] | 0 |

## Paired comparisons (test split)

Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `sparse-only` | `hybrid-rrf` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `sparse-only` | `hybrid-rrf-rerank` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `sparse-only` | `bm25-reference` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `dense-only` | `hybrid-rrf` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `dense-only` | `hybrid-rrf-rerank` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `dense-only` | `bm25-reference` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `hybrid-rrf` | `hybrid-rrf-rerank` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `hybrid-rrf` | `bm25-reference` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |
| `hybrid-rrf-rerank` | `bm25-reference` | 0 | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference | +0.000 [+0.00, +0.00] — no detectable difference |

## Recall@10 by tag (test split)

| Tag | `sparse-only` | `dense-only` | `hybrid-rrf` | `hybrid-rrf-rerank` | `bm25-reference` |
|---|---|---|---|---|---|

## Misses and violations

- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
