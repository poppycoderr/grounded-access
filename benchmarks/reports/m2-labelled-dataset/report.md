# Retrieval evaluation — dataset v2

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `1044de4`, 108 cases, k=10, policy abac/1, chunker markdown/2, generated 2026-10-01T16:35:51+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Retrieval plans: `sparse-only` `e09e5b51cd5c36ac`; `dense-only` `d489d865e16bcda9`; `hybrid-rrf` `bd71e8ef3c45267b`.

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 20 | 0.900 [0.75, 1.00] | 1.000 [1.00, 1.00] | 0.615 [0.46, 0.78] | 0.707 [0.59, 0.83] | 0 |
| `dense-only` | 20 | 0.950 [0.85, 1.00] | 0.950 [0.85, 1.00] | 0.850 [0.70, 0.97] | 0.875 [0.75, 0.97] | 0 |
| `hybrid-rrf` | 20 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.793 [0.66, 0.92] | 0.846 [0.75, 0.94] | 0 |
| `bm25-reference` | 20 | 0.900 [0.75, 1.00] | 0.950 [0.85, 1.00] | 0.634 [0.48, 0.78] | 0.712 [0.58, 0.84] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 57 | 0.816 [0.71, 0.91] | 0.930 [0.86, 0.98] | 0.640 [0.54, 0.74] | 0.711 [0.63, 0.79] | 0 |
| `dense-only` | 57 | 0.921 [0.84, 0.98] | 0.965 [0.91, 1.00] | 0.856 [0.78, 0.93] | 0.884 [0.81, 0.94] | 0 |
| `hybrid-rrf` | 57 | 0.947 [0.88, 1.00] | 0.965 [0.91, 1.00] | 0.797 [0.71, 0.88] | 0.837 [0.77, 0.90] | 0 |
| `bm25-reference` | 57 | 0.851 [0.75, 0.94] | 0.921 [0.84, 0.98] | 0.689 [0.59, 0.78] | 0.744 [0.66, 0.82] | 0 |

## Paired comparisons (test split)

Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 57 | +0.035 [-0.04, +0.11] — no detectable difference | +0.216 [+0.12, +0.31] — better | +0.172 [+0.09, +0.25] — better |
| `sparse-only` | `hybrid-rrf` | 57 | +0.035 [+0.00, +0.09] — no detectable difference | +0.157 [+0.10, +0.22] — better | +0.126 [+0.08, +0.18] — better |
| `sparse-only` | `bm25-reference` | 57 | -0.009 [-0.06, +0.04] — no detectable difference | +0.049 [-0.02, +0.12] — no detectable difference | +0.032 [-0.02, +0.08] — no detectable difference |
| `dense-only` | `hybrid-rrf` | 57 | +0.000 [-0.05, +0.05] — no detectable difference | -0.059 [-0.13, +0.01] — no detectable difference | -0.047 [-0.10, +0.01] — no detectable difference |
| `dense-only` | `bm25-reference` | 57 | -0.044 [-0.11, +0.02] — no detectable difference | -0.167 [-0.26, -0.07] — worse | -0.140 [-0.22, -0.06] — worse |
| `hybrid-rrf` | `bm25-reference` | 57 | -0.044 [-0.11, +0.00] — no detectable difference | -0.108 [-0.18, -0.04] — worse | -0.093 [-0.15, -0.04] — worse |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `sparse-only` | 26 | 8 |
| `dense-only` | 26 | 9 |
| `hybrid-rrf` | 26 | 8 |
| `bm25-reference` | 26 | 9 |

## Recall@10 by tag (test split)

| Tag | `sparse-only` | `dense-only` | `hybrid-rrf` | `bm25-reference` |
|---|---|---|---|---|
| abbreviation | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| api | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| authorization | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) |
| backup | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| clearance | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) |
| cross-tenant | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| department | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| hard-negative | 1.00 (n=18) | 1.00 (n=18) | 1.00 (n=18) | 1.00 (n=18) |
| identical-wording | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| incident | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| multi-document | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| multi-section | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 0.75 (n=2) |
| numbers | 1.00 (n=24) | 1.00 (n=24) | 1.00 (n=24) | 0.96 (n=24) |
| oncall | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| paraphrase | 0.87 (n=31) | 0.94 (n=31) | 0.94 (n=31) | 0.87 (n=31) |
| policy | 0.73 (n=11) | 1.00 (n=11) | 0.91 (n=11) | 0.82 (n=11) |
| project | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) |
| runbook | 1.00 (n=6) | 1.00 (n=6) | 1.00 (n=6) | 0.92 (n=6) |
| sales | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| security | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| single-hop | 0.75 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 0.88 (n=8) |
| support | 1.00 (n=5) | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| version | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |

## Misses and violations

- `test` · `sparse-only` · `hr-volunteer-us-paraphrase-002` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `eng-failover-before-after-004` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `dev` · `bm25-reference` · `rw-core-hours-018` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `login-escalate-061` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
