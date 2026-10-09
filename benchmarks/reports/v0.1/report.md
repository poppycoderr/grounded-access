# Retrieval evaluation — dataset v4

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `ea137fb`, 128 cases, k=10, policy abac/1, chunker markdown/2, generated 2026-10-09T15:36:34+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Retrieval plans: `sparse-only` `e09e5b51cd5c36ac`; `dense-only` `d489d865e16bcda9`; `hybrid-rrf` `bd71e8ef3c45267b`; `hybrid-rrf-rerank` `7cb6787822a73cc9`.

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 23 | 0.913 [0.78, 1.00] | 1.000 [1.00, 1.00] | 0.663 [0.51, 0.81] | 0.744 [0.63, 0.85] | 0 |
| `dense-only` | 23 | 0.957 [0.87, 1.00] | 0.957 [0.87, 1.00] | 0.848 [0.72, 0.96] | 0.875 [0.76, 0.96] | 0 |
| `hybrid-rrf` | 23 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.813 [0.69, 0.92] | 0.860 [0.77, 0.94] | 0 |
| `hybrid-rrf-rerank` | 23 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.928 [0.84, 1.00] | 0.946 [0.88, 1.00] | 0 |
| `bm25-reference` | 23 | 0.870 [0.74, 1.00] | 0.957 [0.87, 1.00] | 0.668 [0.52, 0.81] | 0.738 [0.61, 0.85] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 70 | 0.850 [0.76, 0.93] | 0.929 [0.86, 0.99] | 0.690 [0.60, 0.77] | 0.749 [0.67, 0.82] | 0 |
| `dense-only` | 70 | 0.936 [0.87, 0.99] | 0.971 [0.93, 1.00] | 0.865 [0.80, 0.93] | 0.892 [0.83, 0.94] | 0 |
| `hybrid-rrf` | 70 | 0.957 [0.90, 1.00] | 0.971 [0.93, 1.00] | 0.825 [0.75, 0.89] | 0.860 [0.80, 0.91] | 0 |
| `hybrid-rrf-rerank` | 70 | 0.957 [0.91, 0.99] | 0.971 [0.93, 1.00] | 0.957 [0.91, 0.99] | 0.955 [0.91, 0.99] | 0 |
| `bm25-reference` | 70 | 0.864 [0.79, 0.94] | 0.921 [0.86, 0.98] | 0.741 [0.66, 0.82] | 0.784 [0.71, 0.86] | 0 |

## Paired comparisons (test split)

Mean per-case difference, second minus first, with a 95% interval. A difference counts only if the interval excludes zero.

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 70 | +0.043 [-0.01, +0.10] — no detectable difference | +0.176 [+0.10, +0.26] — better | +0.143 [+0.08, +0.21] — better |
| `sparse-only` | `hybrid-rrf` | 70 | +0.043 [+0.00, +0.10] — no detectable difference | +0.135 [+0.08, +0.20] — better | +0.111 [+0.07, +0.16] — better |
| `sparse-only` | `hybrid-rrf-rerank` | 70 | +0.043 [+0.00, +0.10] — no detectable difference | +0.267 [+0.19, +0.35] — better | +0.206 [+0.14, +0.28] — better |
| `sparse-only` | `bm25-reference` | 70 | -0.007 [-0.05, +0.04] — no detectable difference | +0.052 [-0.01, +0.12] — no detectable difference | +0.035 [-0.02, +0.09] — no detectable difference |
| `dense-only` | `hybrid-rrf` | 70 | +0.000 [-0.04, +0.04] — no detectable difference | -0.040 [-0.10, +0.02] — no detectable difference | -0.032 [-0.08, +0.01] — no detectable difference |
| `dense-only` | `hybrid-rrf-rerank` | 70 | +0.000 [-0.04, +0.04] — no detectable difference | +0.092 [+0.04, +0.15] — better | +0.063 [+0.02, +0.11] — better |
| `dense-only` | `bm25-reference` | 70 | -0.050 [-0.11, +0.01] — no detectable difference | -0.124 [-0.21, -0.04] — worse | -0.109 [-0.18, -0.04] — worse |
| `hybrid-rrf` | `hybrid-rrf-rerank` | 70 | +0.000 [+0.00, +0.00] — no detectable difference | +0.132 [+0.07, +0.20] — better | +0.095 [+0.05, +0.14] — better |
| `hybrid-rrf` | `bm25-reference` | 70 | -0.050 [-0.10, -0.01] — worse | -0.084 [-0.15, -0.02] — worse | -0.077 [-0.13, -0.03] — worse |
| `hybrid-rrf-rerank` | `bm25-reference` | 70 | -0.050 [-0.10, -0.01] — worse | -0.216 [-0.30, -0.14] — worse | -0.172 [-0.24, -0.11] — worse |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `sparse-only` | 29 | 8 |
| `dense-only` | 29 | 11 |
| `hybrid-rrf` | 29 | 9 |
| `hybrid-rrf-rerank` | 29 | 8 |
| `bm25-reference` | 29 | 9 |

## Latency (test split)

Request latency seen by the evaluation client on `INTEL(R) XEON(R) PLATINUM 8573C`, HTTP round trip included. It shows what each stage costs on this machine and is not a performance claim.

| Strategy | Requests | p50 ms | p95 ms |
|---|---|---|---|
| `sparse-only` | 94 | 7 | 10 |
| `dense-only` | 94 | 27 | 39 |
| `hybrid-rrf` | 94 | 26 | 36 |
| `hybrid-rrf-rerank` | 94 | 338 | 408 |

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
| hard-negative | 1.00 (n=18) | 1.00 (n=18) | 1.00 (n=18) | 1.00 (n=18) | 1.00 (n=18) |
| identical-wording | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| incident | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| multi-document | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| multi-section | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 0.75 (n=2) |
| numbers | 1.00 (n=31) | 1.00 (n=31) | 1.00 (n=31) | 1.00 (n=31) | 0.97 (n=31) |
| oncall | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| paraphrase | 0.85 (n=33) | 0.94 (n=33) | 0.94 (n=33) | 0.94 (n=33) | 0.85 (n=33) |
| policy | 0.64 (n=11) | 1.00 (n=11) | 0.91 (n=11) | 0.91 (n=11) | 0.73 (n=11) |
| project | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) |
| prompt-injection | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| region | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| runbook | 1.00 (n=6) | 1.00 (n=6) | 1.00 (n=6) | 1.00 (n=6) | 0.92 (n=6) |
| sales | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| scope | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) |
| security | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| single-hop | 0.75 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 0.88 (n=8) |
| support | 1.00 (n=5) | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| validity | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| version | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |

## Misses and violations

- `test` · `sparse-only` · `hr-volunteer-us-paraphrase-002` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `eng-failover-before-after-004` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `dev` · `bm25-reference` · `rw-core-hours-018` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `login-escalate-061` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
