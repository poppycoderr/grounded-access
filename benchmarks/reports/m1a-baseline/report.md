# Retrieval evaluation — dataset v1

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `a608180`, 70 cases, k=10, policy tenant-only/1, generated 2026-09-21T05:31:35+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 16 | 0.938 [0.81, 1.00] | 1.000 [1.00, 1.00] | 0.603 [0.43, 0.78] | 0.698 [0.56, 0.83] | 0 |
| `dense-only` | 16 | 0.938 [0.81, 1.00] | 0.938 [0.81, 1.00] | 0.812 [0.65, 0.96] | 0.844 [0.69, 0.97] | 0 |
| `bm25-reference` | 16 | 0.875 [0.69, 1.00] | 0.938 [0.81, 1.00] | 0.646 [0.46, 0.82] | 0.718 [0.56, 0.86] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 47 | 0.840 [0.73, 0.94] | 0.936 [0.85, 1.00] | 0.616 [0.51, 0.72] | 0.697 [0.61, 0.79] | 0 |
| `dense-only` | 47 | 0.926 [0.85, 0.99] | 0.979 [0.94, 1.00] | 0.860 [0.77, 0.94] | 0.891 [0.82, 0.95] | 0 |
| `bm25-reference` | 47 | 0.883 [0.79, 0.96] | 0.926 [0.85, 0.99] | 0.716 [0.61, 0.82] | 0.765 [0.67, 0.85] | 0 |

## Paired comparisons (test split)

Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 47 | +0.043 [-0.04, +0.13] — no detectable difference | +0.244 [+0.13, +0.35] — better | +0.194 [+0.10, +0.29] — better |
| `sparse-only` | `bm25-reference` | 47 | -0.011 [-0.07, +0.05] — no detectable difference | +0.100 [+0.02, +0.18] — better | +0.068 [+0.00, +0.13] — better |
| `dense-only` | `bm25-reference` | 47 | -0.053 [-0.14, +0.02] — no detectable difference | -0.144 [-0.25, -0.03] — worse | -0.126 [-0.22, -0.03] — worse |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `sparse-only` | 16 | 2 |
| `dense-only` | 16 | 2 |
| `bm25-reference` | 16 | 3 |

## Recall@10 by tag (test split)

| Tag | `sparse-only` | `dense-only` | `bm25-reference` |
|---|---|---|---|
| abbreviation | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| api | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| backup | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| cross-tenant | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| hard-negative | 1.00 (n=14) | 1.00 (n=14) | 1.00 (n=14) |
| identical-wording | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| incident | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| multi-document | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| multi-section | 1.00 (n=2) | 1.00 (n=2) | 0.75 (n=2) |
| numbers | 1.00 (n=19) | 1.00 (n=19) | 0.95 (n=19) |
| oncall | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| paraphrase | 0.88 (n=26) | 0.96 (n=26) | 0.88 (n=26) |
| policy | 0.73 (n=11) | 1.00 (n=11) | 0.82 (n=11) |
| runbook | 1.00 (n=6) | 1.00 (n=6) | 0.92 (n=6) |
| sales | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| security | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| single-hop | 0.75 (n=8) | 1.00 (n=8) | 0.88 (n=8) |
| support | 1.00 (n=5) | 0.80 (n=5) | 1.00 (n=5) |

## Misses and violations

- `test` · `sparse-only` · `hr-volunteer-us-paraphrase-002` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `eng-failover-before-after-004` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `dev` · `bm25-reference` · `rw-core-hours-018` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `login-escalate-061` · recall@10=0.0 · violations=none
