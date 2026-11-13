# Retrieval evaluation — dataset v5

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `d0c012f`, 333 cases, k=10, policy abac/1, chunker markdown/4, generated 2026-11-13T10:46:15+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Measured: server commit `d0c012f47a1a91d4fedf981c36761f625a9f6739` (0.1.0-SNAPSHOT), index `BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55`, embedding context on, chunker release 4 (180/30 words), reranker `Xenova/ms-marco-MiniLM-L-12-v2@42a4a787e30451cf9dbd09080c2a5b8dde332c1e`, server CPU `AMD EPYC 9V74 80-Core Processor` (4 processors), corpus `c783e312846a` (2965 chunks as listed). Evaluator commit `d0c012f`, 0 warmup requests, up to 3 attempts per request, metrics version 2.

Retrieval plans: `sparse-only` `d7967398b8117b5f`; `dense-only` `d489d865e16bcda9`; `hybrid-rrf` `2598002ea9c41e8d`; `hybrid-rrf-rerank` `748ba4f7e675effe`.

`bm25-reference` is not a system configuration: it is Okapi BM25 computed offline over the same authorized chunks with their heading paths, as the keyword channel indexes them, to show how much of any gap comes from PostgreSQL FTS having no corpus statistics.

128 of 333 cases have labels a person checked (ADR-0015). Summaries, paired comparisons and the tag table count only those. Tables marked as including unreviewed cases count every case; the security gate checks every case.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 23 | 0.652 [0.43, 0.83] | 0.739 [0.57, 0.91] | 0.350 [0.22, 0.48] | 0.445 [0.31, 0.58] | 0 |
| `dense-only` | 23 | 0.913 [0.78, 1.00] | 0.957 [0.87, 1.00] | 0.877 [0.75, 0.98] | 0.896 [0.78, 0.98] | 0 |
| `hybrid-rrf` | 23 | 0.870 [0.74, 1.00] | 0.913 [0.78, 1.00] | 0.686 [0.53, 0.83] | 0.742 [0.60, 0.87] | 0 |
| `hybrid-rrf-rerank` | 23 | 0.957 [0.87, 1.00] | 0.957 [0.87, 1.00] | 0.842 [0.70, 0.96] | 0.870 [0.75, 0.96] | 0 |
| `bm25-reference` | 23 | 0.870 [0.74, 1.00] | 0.870 [0.74, 1.00] | 0.717 [0.55, 0.87] | 0.756 [0.60, 0.89] | 0 |

### dev split with unreviewed cases — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 62 | 0.694 [0.58, 0.81] | 0.823 [0.73, 0.92] | 0.535 [0.43, 0.64] | 0.600 [0.51, 0.69] | 0 |
| `dense-only` | 62 | 0.919 [0.84, 0.98] | 0.952 [0.89, 1.00] | 0.873 [0.80, 0.94] | 0.889 [0.82, 0.95] | 0 |
| `hybrid-rrf` | 62 | 0.855 [0.76, 0.94] | 0.887 [0.81, 0.95] | 0.750 [0.65, 0.84] | 0.780 [0.69, 0.86] | 0 |
| `hybrid-rrf-rerank` | 62 | 0.903 [0.82, 0.97] | 0.919 [0.84, 0.98] | 0.816 [0.73, 0.90] | 0.838 [0.76, 0.91] | 0 |
| `bm25-reference` | 62 | 0.919 [0.84, 0.98] | 0.935 [0.87, 0.98] | 0.771 [0.68, 0.85] | 0.809 [0.73, 0.88] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 70 | 0.564 [0.45, 0.68] | 0.750 [0.65, 0.84] | 0.437 [0.34, 0.53] | 0.506 [0.41, 0.59] | 0 |
| `dense-only` | 70 | 0.936 [0.88, 0.99] | 0.964 [0.92, 1.00] | 0.804 [0.73, 0.88] | 0.836 [0.77, 0.90] | 0 |
| `hybrid-rrf` | 70 | 0.879 [0.80, 0.95] | 0.921 [0.86, 0.98] | 0.707 [0.62, 0.79] | 0.749 [0.67, 0.82] | 0 |
| `hybrid-rrf-rerank` | 70 | 0.929 [0.86, 0.98] | 0.950 [0.89, 0.99] | 0.826 [0.75, 0.89] | 0.846 [0.78, 0.90] | 0 |
| `bm25-reference` | 70 | 0.743 [0.64, 0.84] | 0.793 [0.70, 0.89] | 0.625 [0.53, 0.72] | 0.660 [0.57, 0.75] | 0 |

### test split with unreviewed cases — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `sparse-only` | 206 | 0.684 [0.62, 0.75] | 0.825 [0.77, 0.87] | 0.506 [0.45, 0.56] | 0.574 [0.52, 0.62] | 0 |
| `dense-only` | 206 | 0.930 [0.90, 0.96] | 0.951 [0.92, 0.98] | 0.819 [0.78, 0.86] | 0.842 [0.80, 0.88] | 0 |
| `hybrid-rrf` | 206 | 0.908 [0.87, 0.94] | 0.942 [0.91, 0.97] | 0.755 [0.71, 0.80] | 0.790 [0.75, 0.83] | 0 |
| `hybrid-rrf-rerank` | 206 | 0.937 [0.90, 0.97] | 0.966 [0.94, 0.99] | 0.841 [0.80, 0.88] | 0.860 [0.82, 0.89] | 0 |
| `bm25-reference` | 206 | 0.799 [0.74, 0.85] | 0.854 [0.81, 0.90] | 0.674 [0.62, 0.73] | 0.707 [0.66, 0.76] | 0 |

## Paired comparisons (test split, exploratory)

Mean per-case difference, second minus first, with an unadjusted 95% interval. The verdict uses bootstrap p-values Holm-adjusted across all 30 comparisons of this table: a difference counts only if its adjusted p-value is below 0.05. A change is judged by the primary comparison it declares, not by this table (ADR-0016).

| First | Second | Cases | ΔRecall@10 | ΔMRR@10 | ΔEC@10 |
|---|---|---|---|---|---|
| `sparse-only` | `dense-only` | 70 | +0.214 [+0.11, +0.32] — better | +0.367 [+0.27, +0.46] — better | +0.330 [+0.24, +0.42] — better |
| `sparse-only` | `hybrid-rrf` | 70 | +0.171 [+0.09, +0.26] — better | +0.269 [+0.20, +0.34] — better | +0.243 [+0.18, +0.31] — better |
| `sparse-only` | `hybrid-rrf-rerank` | 70 | +0.200 [+0.11, +0.30] — better | +0.389 [+0.30, +0.48] — better | +0.340 [+0.26, +0.43] — better |
| `sparse-only` | `bm25-reference` | 70 | +0.043 [-0.05, +0.14] — no detectable difference | +0.188 [+0.11, +0.27] — better | +0.154 [+0.08, +0.23] — better |
| `dense-only` | `hybrid-rrf` | 70 | -0.043 [-0.10, +0.01] — no detectable difference | -0.098 [-0.18, -0.02] — no detectable difference | -0.087 [-0.16, -0.02] — no detectable difference |
| `dense-only` | `hybrid-rrf-rerank` | 70 | -0.014 [-0.07, +0.04] — no detectable difference | +0.022 [-0.04, +0.08] — no detectable difference | +0.010 [-0.04, +0.06] — no detectable difference |
| `dense-only` | `bm25-reference` | 70 | -0.171 [-0.27, -0.09] — worse | -0.179 [-0.27, -0.09] — worse | -0.176 [-0.26, -0.10] — worse |
| `hybrid-rrf` | `hybrid-rrf-rerank` | 70 | +0.029 [-0.01, +0.08] — no detectable difference | +0.119 [+0.05, +0.19] — better | +0.097 [+0.04, +0.16] — better |
| `hybrid-rrf` | `bm25-reference` | 70 | -0.129 [-0.21, -0.06] — worse | -0.082 [-0.17, +0.00] — no detectable difference | -0.089 [-0.17, -0.02] — no detectable difference |
| `hybrid-rrf-rerank` | `bm25-reference` | 70 | -0.157 [-0.25, -0.07] — worse | -0.201 [-0.28, -0.12] — worse | -0.186 [-0.26, -0.11] — worse |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `sparse-only` | 29 | 7 |
| `dense-only` | 29 | 9 |
| `hybrid-rrf` | 29 | 9 |
| `hybrid-rrf-rerank` | 29 | 9 |
| `bm25-reference` | 29 | 6 |

## Latency (test split)

Request latency seen by the evaluation client on `AMD EPYC 9V74 80-Core Processor`, HTTP round trip included. It shows what each stage costs on this machine and is not a performance claim.

| Strategy | Requests | p50 ms | p95 ms |
|---|---|---|---|
| `sparse-only` | 94 | 21 | 34 |
| `dense-only` | 94 | 37 | 60 |
| `hybrid-rrf` | 94 | 47 | 75 |
| `hybrid-rrf-rerank` | 94 | 608 | 1119 |

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
| abbreviation | 0.80 (n=5) | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.60 (n=5) |
| api | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| authorization | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) | 0.90 (n=10) |
| backup | 0.75 (n=4) | 0.75 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| clearance | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) |
| cross-tenant | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |
| department | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| hard-negative | 0.89 (n=18) | 0.92 (n=18) | 0.92 (n=18) | 0.94 (n=18) | 0.86 (n=18) |
| identical-wording | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| incident | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) | 1.00 (n=1) |
| lexical | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) | 1.00 (n=3) |
| multi-document | 1.00 (n=1) | 0.50 (n=1) | 0.50 (n=1) | 1.00 (n=1) | 0.50 (n=1) |
| multi-section | 0.75 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 0.75 (n=2) | 1.00 (n=2) |
| numbers | 0.87 (n=31) | 0.97 (n=31) | 1.00 (n=31) | 1.00 (n=31) | 0.90 (n=31) |
| oncall | 0.60 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.60 (n=5) |
| paraphrase | 0.52 (n=33) | 0.97 (n=33) | 0.85 (n=33) | 0.91 (n=33) | 0.61 (n=33) |
| policy | 0.50 (n=11) | 1.00 (n=11) | 0.82 (n=11) | 0.91 (n=11) | 0.64 (n=11) |
| project | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) | 0.50 (n=2) |
| prompt-injection | 0.80 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) |
| region | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) |
| runbook | 1.00 (n=6) | 0.92 (n=6) | 0.92 (n=6) | 0.92 (n=6) | 0.92 (n=6) |
| sales | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 1.00 (n=5) | 0.80 (n=5) |
| scope | 0.88 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 0.88 (n=8) |
| security | 0.20 (n=5) | 1.00 (n=5) | 0.80 (n=5) | 1.00 (n=5) | 0.20 (n=5) |
| single-hop | 0.75 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 1.00 (n=8) | 0.88 (n=8) |
| support | 0.60 (n=5) | 1.00 (n=5) | 0.80 (n=5) | 0.80 (n=5) | 0.80 (n=5) |
| validity | 0.75 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 1.00 (n=4) | 0.75 (n=4) |
| version | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) | 1.00 (n=2) |

## Keyword rankings

Every case of `sparse-only`, `bm25-reference` ranked as the committed expected rankings say.

## Misses and violations

- `test` · `sparse-only` · `hr-volunteer-us-paraphrase-002` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `hr-volunteer-us-paraphrase-002` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `eng-failover-before-after-004` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `hr-travel-meal-010` · recall@10=0.0 · violations=none
- `dev` · `sparse-only` · `rw-core-hours-018` · recall@10=0.0 · violations=none
- `dev` · `bm25-reference` · `rw-core-hours-018` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `rw-abroad-019` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `pl-return-022` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `rel-resign-025` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-escalate-031` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `oncall-pay-034` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `oncall-rotation-035` · recall@10=0.0 · violations=none
- `dev` · `sparse-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `dev` · `dense-only` · `oncall-night-036` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf` · `oncall-night-036` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf-rerank` · `oncall-night-036` · recall@10=0.0 · violations=none
- `dev` · `bm25-reference` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `backup-rpo-037` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `backup-region-041` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `sec-mfa-042` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `sec-mfa-042` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `sec-keys-043` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `sec-keys-043` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `sec-keys-043` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `sec-offboard-044` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `sec-offboard-044` · recall@10=0.0 · violations=none
- `dev` · `sparse-only` · `sec-rotate-045` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf` · `sec-rotate-045` · recall@10=0.0 · violations=none
- `dev` · `bm25-reference` · `sec-rotate-045` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `sec-review-046` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `sec-review-046` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `refund-monthly-047` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `refund-monthly-047` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `refund-monthly-047` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `refund-monthly-047` · recall@10=0.0 · violations=none
- `dev` · `sparse-only` · `refund-approval-049` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `credit-overcharge-050` · recall@10=0.0 · violations=none
- `dev` · `sparse-only` · `sla-maintenance-053` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `multi-sev1-billing-055` · recall@10=0.5 · violations=none
- `test` · `hybrid-rrf` · `multi-sev1-billing-055` · recall@10=0.5 · violations=none
- `test` · `bm25-reference` · `multi-sev1-billing-055` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `multi-remote-056` · recall@10=0.5 · violations=none
- `test` · `sparse-only` · `travel-late-057` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `product-sso-059` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `dev` · `sparse-only` · `authz-secrets-082` · recall@10=0.0 · violations=none
- `test` · `bm25-reference` · `scope-meal-2025-us-117` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `scope-receipts-2025-118` · recall@10=0.0 · violations=none
- `test` · `sparse-only` · `inject-refund-paraphrase-128` · recall@10=0.0 · violations=none
- `test` · `dense-only` · `deploy-canary-error-rate-129` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `deploy-canary-error-rate-129` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `deploy-friday-132` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `deploy-friday-132` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `sparse-only` · `pg-window-estimate-135` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `sparse-only` · `log-legal-hold-138` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf` · `log-legal-hold-138` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf-rerank` · `log-legal-hold-138` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `bm25-reference` · `log-legal-hold-138` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `support-p2-response-141` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `learning-carryover-149` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `learning-carryover-149` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `ir-power-off-151` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `ir-power-off-151` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `ir-power-off-151` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `ir-cloud-credentials-153` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `vendor-tier1-review-156` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `search-stale-cause-163` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `sparse-only` · `search-console-age-166` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf` · `search-console-age-166` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf-rerank` · `search-console-age-166` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `obs-cause-alerts-169` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `obs-cause-alerts-169` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf-rerank` · `obs-cause-alerts-169` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `sparse-only` · `obs-noisy-alerts-170` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf` · `obs-noisy-alerts-170` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf-rerank` · `obs-noisy-alerts-170` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `hiring-work-sample-178` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `hiring-work-sample-178` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `hiring-work-sample-178` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `phishing-clicked-183` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `encryption-usb-216` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `dr-tier1-target-225` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `dr-tier1-target-225` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `owner-identity-escalation-234` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `owner-identity-escalation-234` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `sparse-only` · `ic-only-fixer-240` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `dense-only` · `ic-only-fixer-240` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf` · `ic-only-fixer-240` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf-rerank` · `ic-only-fixer-240` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `multi-customer-updates-243` · recall@10=0.5 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `multi-customer-updates-243` · recall@10=0.5 · violations=none · unreviewed
- `test` · `bm25-reference` · `scope-p2-normal-262` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `whistleblowing-feedback-277` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `dense-only` · `survey-anonymity-282` · recall@10=0.0 · violations=none · unreviewed
- `dev` · `hybrid-rrf` · `survey-anonymity-282` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `weekend-access-288` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `multi-leaving-access-293` · recall@10=0.5 · violations=none · unreviewed
- `test` · `dense-only` · `multi-leaving-access-293` · recall@10=0.5 · violations=none · unreviewed
- `test` · `bm25-reference` · `multi-leaving-access-293` · recall@10=0.5 · violations=none · unreviewed
- `test` · `sparse-only` · `languages-other-319` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `languages-other-319` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `languages-other-319` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `survey-low-score-320` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `survey-low-score-320` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `survey-low-score-320` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf-rerank` · `survey-low-score-320` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `survey-low-score-320` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `subprocessor-notice-327` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `authority-requests-333` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `training-late-340` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `multi-outage-tickets-343` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `multi-outage-tickets-343` · recall@10=0.5 · violations=none · unreviewed
- `test` · `hybrid-rrf-rerank` · `multi-outage-tickets-343` · recall@10=0.5 · violations=none · unreviewed
- `test` · `sparse-only` · `owner-role-351` · recall@10=0.0 · violations=none · unreviewed
- `test` · `bm25-reference` · `owner-role-351` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `audit-log-retention-352` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `recovery-codes-354` · recall@10=0.0 · violations=none · unreviewed
- `test` · `dense-only` · `recovery-codes-354` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf` · `recovery-codes-354` · recall@10=0.0 · violations=none · unreviewed
- `test` · `hybrid-rrf-rerank` · `recovery-codes-354` · recall@10=0.0 · violations=none · unreviewed
- `test` · `sparse-only` · `accounting-conflict-355` · recall@10=0.0 · violations=none · unreviewed
