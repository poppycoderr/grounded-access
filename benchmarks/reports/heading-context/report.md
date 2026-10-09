# Retrieval evaluation — dataset v4

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `29b5bab`, 128 cases, k=10, policy abac/1, chunker markdown/3, generated 2026-10-09T17:25:46+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Retrieval plans: `sparse-only` `d7967398b8117b5f`; `dense-only` `d489d865e16bcda9`; `hybrid-rrf` `2598002ea9c41e8d`; `hybrid-rrf-rerank` `748ba4f7e675effe`.

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks with their heading paths, as the keyword channel indexes them, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 23 | 0.957 [0.87, 1.00] | 1.000 [1.00, 1.00] | 0.704 [0.56, 0.84] | 0.777 [0.67, 0.88] | 0 |
| `dense-only` | 23 | 0.957 [0.87, 1.00] | 0.957 [0.87, 1.00] | 0.906 [0.79, 1.00] | 0.919 [0.81, 1.00] | 0 |
| `hybrid-rrf` | 23 | 0.957 [0.87, 1.00] | 1.000 [1.00, 1.00] | 0.882 [0.76, 0.98] | 0.911 [0.82, 0.98] | 0 |
| `hybrid-rrf-rerank` | 23 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.928 [0.84, 1.00] | 0.946 [0.88, 1.00] | 0 |
| `bm25-reference` | 23 | 0.870 [0.74, 1.00] | 1.000 [1.00, 1.00] | 0.747 [0.60, 0.88] | 0.808 [0.70, 0.91] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 70 | 0.886 [0.81, 0.95] | 0.943 [0.89, 0.99] | 0.740 [0.66, 0.82] | 0.785 [0.71, 0.85] | 0 |
| `dense-only` | 70 | 0.964 [0.92, 1.00] | 0.979 [0.94, 1.00] | 0.895 [0.84, 0.95] | 0.915 [0.87, 0.96] | 0 |
| `hybrid-rrf` | 70 | 0.950 [0.89, 0.99] | 0.986 [0.96, 1.00] | 0.867 [0.80, 0.93] | 0.892 [0.84, 0.94] | 0 |
| `hybrid-rrf-rerank` | 70 | 0.971 [0.93, 1.00] | 0.986 [0.96, 1.00] | 0.971 [0.94, 1.00] | 0.970 [0.93, 0.99] | 0 |
| `bm25-reference` | 70 | 0.907 [0.84, 0.97] | 0.943 [0.89, 0.99] | 0.772 [0.69, 0.85] | 0.812 [0.74, 0.88] | 0 |

## Paired comparisons (test split)

Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 70 | +0.036 [-0.01, +0.09] — no detectable difference | +0.155 [+0.07, +0.25] — better | +0.130 [+0.06, +0.20] — better |
| `sparse-only` | `hybrid-rrf` | 70 | +0.043 [+0.00, +0.10] — no detectable difference | +0.127 [+0.07, +0.19] — better | +0.108 [+0.06, +0.16] — better |
| `sparse-only` | `hybrid-rrf-rerank` | 70 | +0.043 [+0.00, +0.10] — no detectable difference | +0.232 [+0.15, +0.31] — better | +0.185 [+0.12, +0.25] — better |
| `sparse-only` | `bm25-reference` | 70 | +0.000 [-0.04, +0.04] — no detectable difference | +0.032 [-0.03, +0.10] — no detectable difference | +0.028 [-0.02, +0.08] — no detectable difference |
| `dense-only` | `hybrid-rrf` | 70 | +0.007 [+0.00, +0.02] — no detectable difference | -0.029 [-0.09, +0.03] — no detectable difference | -0.022 [-0.07, +0.02] — no detectable difference |
| `dense-only` | `hybrid-rrf-rerank` | 70 | +0.007 [+0.00, +0.02] — no detectable difference | +0.076 [+0.03, +0.13] — better | +0.055 [+0.02, +0.10] — better |
| `dense-only` | `bm25-reference` | 70 | -0.036 [-0.09, +0.01] — no detectable difference | -0.124 [-0.21, -0.04] — worse | -0.102 [-0.18, -0.03] — worse |
| `hybrid-rrf` | `hybrid-rrf-rerank` | 70 | +0.000 [+0.00, +0.00] — no detectable difference | +0.105 [+0.05, +0.17] — better | +0.077 [+0.03, +0.12] — better |
| `hybrid-rrf` | `bm25-reference` | 70 | -0.043 [-0.10, +0.00] — no detectable difference | -0.095 [-0.17, -0.02] — worse | -0.080 [-0.14, -0.02] — worse |
| `hybrid-rrf-rerank` | `bm25-reference` | 70 | -0.043 [-0.10, +0.00] — no detectable difference | -0.200 [-0.28, -0.13] — worse | -0.157 [-0.23, -0.10] — worse |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `sparse-only` | 29 | 9 |
| `dense-only` | 29 | 9 |
| `hybrid-rrf` | 29 | 10 |
| `hybrid-rrf-rerank` | 29 | 8 |
| `bm25-reference` | 29 | 9 |

## Latency (test split)

Request latency seen by the evaluation client on `AMD EPYC 7763 64-Core Processor`, HTTP round trip included. It shows what each stage costs on this machine and is not a performance claim.

| Strategy | Requests | p50 ms | p95 ms |
|---|---|---|---|
| `sparse-only` | 94 | 7 | 12 |
| `dense-only` | 94 | 41 | 60 |
| `hybrid-rrf` | 94 | 44 | 65 |
| `hybrid-rrf-rerank` | 94 | 469 | 587 |

## Scope (test split)

Cases that set a region or a date, or name documents that are readable but do not apply. Returning such a document is a scope failure. It is counted here and never as a security violation.

| Strategy | Scope cases | Scope failures |
|---|---|---|
| `sparse-only` | 10 | 0 |
| `dense-only` | 10 | 0 |
| `hybrid-rrf` | 10 | 0 |
| `hybrid-rrf-rerank` | 10 | 0 |
| `bm25-reference` | 10 | 0 |

## Recall@10 by tag (test split)

| Tag | `sparse-only` | `dense-only` | `hybrid-rrf` | `hybrid-rrf-rerank` | `bm25-reference` |
|---|---|---|---|---|---|
| abbreviation | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| api | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| authorization | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) |
| backup | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| clearance | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) |
| cross-tenant | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| department | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| hard-negative | 0.94 (n=18) | 0.97 (n=18) | 1.00 (n=18) | 1.00 (n=18) | 1.00 (n=18) |
| identical-wording | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| incident | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| multi-document | 1.00 (n=1) | 0.50 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| multi-section | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| numbers | 1.00 (n=31) | 1.00 (n=31) | 1.00 (n=31) | 1.00 (n=31) | 0.97 (n=31) |
| oncall | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| paraphrase | 0.88 (n=33) | 0.97 (n=33) | 0.97 (n=33) | 0.97 (n=33) | 0.88 (n=33) |
| policy | 0.91 (n=11) | 1.00 (n=11) | 1.00 (n=11) | 1.00 (n=11) | 0.91 (n=11) |
| project | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) |
| prompt-injection | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| region | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| runbook | 1.00 (n=6) | 0.92 (n=6) | 1.00 (n=6) | 1.00 (n=6) | 1.00 (n=6) |
| sales | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| scope | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) |
| security | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| single-hop | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) |
| support | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| validity | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| version | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |

## Misses and violations

- `test` · `sparse-only` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `sec-keys-043` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `sec-keys-043` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `refund-monthly-047` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `multi-sev1-billing-055` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
