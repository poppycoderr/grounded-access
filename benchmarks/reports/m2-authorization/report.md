# Retrieval evaluation — dataset v3

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `8ccf91a`, 122 cases, k=10, policy abac/1, chunker markdown/2, generated 2026-10-02T17:43:37+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Retrieval plans: `sparse-only` `e09e5b51cd5c36ac`; `dense-only` `d489d865e16bcda9`; `hybrid-rrf` `bd71e8ef3c45267b`.

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 22 | 0.909 [0.77, 1.00] | 1.000 [1.00, 1.00] | 0.650 [0.50, 0.80] | 0.734 [0.62, 0.85] | 0 |
| `dense-only` | 22 | 0.955 [0.86, 1.00] | 0.955 [0.86, 1.00] | 0.864 [0.73, 0.97] | 0.886 [0.77, 0.98] | 0 |
| `hybrid-rrf` | 22 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.812 [0.69, 0.92] | 0.860 [0.77, 0.94] | 0 |
| `bm25-reference` | 22 | 0.909 [0.77, 1.00] | 0.955 [0.86, 1.00] | 0.659 [0.51, 0.81] | 0.732 [0.61, 0.85] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 65 | 0.838 [0.75, 0.92] | 0.923 [0.85, 0.98] | 0.683 [0.59, 0.77] | 0.742 [0.66, 0.82] | 0 |
| `dense-only` | 65 | 0.931 [0.87, 0.98] | 0.969 [0.92, 1.00] | 0.873 [0.80, 0.94] | 0.898 [0.84, 0.95] | 0 |
| `hybrid-rrf` | 65 | 0.954 [0.89, 1.00] | 0.969 [0.92, 1.00] | 0.822 [0.74, 0.89] | 0.857 [0.79, 0.91] | 0 |
| `bm25-reference` | 65 | 0.869 [0.78, 0.95] | 0.931 [0.87, 0.98] | 0.720 [0.63, 0.80] | 0.770 [0.69, 0.84] | 0 |

## Paired comparisons (test split)

Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 65 | +0.046 [-0.02, +0.11] — no detectable difference | +0.191 [+0.11, +0.28] — better | +0.156 [+0.09, +0.23] — better |
| `sparse-only` | `hybrid-rrf` | 65 | +0.046 [+0.00, +0.11] — no detectable difference | +0.139 [+0.09, +0.20] — better | +0.115 [+0.07, +0.16] — better |
| `sparse-only` | `bm25-reference` | 65 | +0.008 [-0.05, +0.06] — no detectable difference | +0.037 [-0.02, +0.10] — no detectable difference | +0.028 [-0.02, +0.08] — no detectable difference |
| `dense-only` | `hybrid-rrf` | 65 | +0.000 [-0.05, +0.05] — no detectable difference | -0.051 [-0.11, +0.01] — no detectable difference | -0.041 [-0.09, +0.01] — no detectable difference |
| `dense-only` | `bm25-reference` | 65 | -0.038 [-0.10, +0.02] — no detectable difference | -0.154 [-0.24, -0.07] — worse | -0.128 [-0.20, -0.06] — worse |
| `hybrid-rrf` | `bm25-reference` | 65 | -0.038 [-0.09, +0.00] — no detectable difference | -0.102 [-0.16, -0.04] — worse | -0.087 [-0.14, -0.04] — worse |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `sparse-only` | 26 | 8 |
| `dense-only` | 26 | 9 |
| `hybrid-rrf` | 26 | 8 |
| `bm25-reference` | 26 | 9 |

## Scope (test split)

Cases that set a region or a date, or name documents that are readable but do not apply. Returning such a document is a scope failure. It is counted here and never as a security violation.

| Strategy | Scope cases | Scope failures |
|---|---|---|
| `sparse-only` | 10 | 0 |
| `dense-only` | 10 | 0 |
| `hybrid-rrf` | 10 | 0 |
| `bm25-reference` | 10 | 0 |

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
| identical-wording | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| incident | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| multi-document | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| multi-section | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 0.75 (n=2) |
| numbers | 1.00 (n=29) | 1.00 (n=29) | 1.00 (n=29) | 0.97 (n=29) |
| oncall | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| paraphrase | 0.84 (n=32) | 0.94 (n=32) | 0.94 (n=32) | 0.88 (n=32) |
| policy | 0.64 (n=11) | 1.00 (n=11) | 0.91 (n=11) | 0.82 (n=11) |
| project | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) |
| region | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| runbook | 1.00 (n=6) | 1.00 (n=6) | 1.00 (n=6) | 0.92 (n=6) |
| sales | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| scope | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) |
| security | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| single-hop | 0.75 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 0.88 (n=8) |
| support | 1.00 (n=5) | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| validity | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
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
- `test` · `sparse-only` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `login-escalate-061` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
