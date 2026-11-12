# Retrieval evaluation — dataset v5

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `cac172a`, 372 cases, k=10, policy abac/1, chunker markdown/4, generated 2026-11-12T14:57:35+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Measured: server commit `unknown` (0.1.0-SNAPSHOT), index `BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55`, embedding context on, chunker release 4 (180/30 words), reranker `Xenova/ms-marco-MiniLM-L-12-v2@42a4a787e30451cf9dbd09080c2a5b8dde332c1e`, server CPU `aarch64` (6 processors), corpus `c783e312846a` (2965 chunks as listed). Evaluator commit `cac172a` with uncommitted changes, 0 warmup requests, up to 3 attempts per request, metrics version 2.

Retrieval plans: `hybrid-rrf-rerank` `748ba4f7e675effe`.

2 request(s) were answered degraded and sent again; only complete answers are scored. This happens when a model call exceeds its timeout on a busy machine.

**This run is invalid and incomplete:** case obs-sample-rule-172 · hybrid-rrf-rerank was answered degraded (rerank_unavailable) 3 times in a row; the run is invalid. It is kept as evidence, not as a result.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 31 | 0.839 [0.71, 0.97] | 0.871 [0.74, 0.97] | 0.759 [0.61, 0.89] | 0.786 [0.65, 0.90] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 97 | 0.928 [0.87, 0.97] | 0.954 [0.91, 0.99] | 0.817 [0.75, 0.88] | 0.843 [0.79, 0.89] | 0 |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `hybrid-rrf-rerank` | 37 | 11 |

## Latency (test split)

Request latency seen by the evaluation client on `arm`, HTTP round trip included. It shows what each stage costs on this machine and is not a performance claim.

| Strategy | Requests | p50 ms | p95 ms |
|---|---|---|---|
| `hybrid-rrf-rerank` | 127 | 1094 | 2169 |

## Scope (test split)

Cases that set a region or a date, or name documents that are readable but do not apply. Returning such a document is a scope failure. It is counted here and never as a security violation.

| Strategy | Scope cases | Scope failures |
|---|---|---|
| `hybrid-rrf-rerank` | 10 | 0 |

## Recall@10 by tag (test split)

| Tag | `hybrid-rrf-rerank` |
|---|---|
| abbreviation | 0.83 (n=6) |
| api | 1.00 (n=5) |
| authorization | 0.90 (n=10) |
| backup | 1.00 (n=4) |
| clearance | 0.80 (n=5) |
| cross-tenant | 1.00 (n=2) |
| department | 1.00 (n=4) |
| hard-negative | 0.92 (n=24) |
| identical-wording | 1.00 (n=5) |
| incident | 1.00 (n=5) |
| lexical | 1.00 (n=4) |
| long-section | 0.90 (n=10) |
| multi-document | 1.00 (n=1) |
| multi-section | 0.75 (n=2) |
| numbers | 1.00 (n=48) |
| oncall | 0.86 (n=7) |
| paraphrase | 0.92 (n=50) |
| policy | 0.94 (n=17) |
| project | 0.67 (n=3) |
| prompt-injection | 1.00 (n=5) |
| region | 1.00 (n=4) |
| runbook | 0.95 (n=11) |
| sales | 1.00 (n=5) |
| scope | 1.00 (n=8) |
| security | 1.00 (n=10) |
| single-hop | 1.00 (n=8) |
| space-free | 1.00 (n=2) |
| support | 0.86 (n=7) |
| table | 1.00 (n=6) |
| validity | 1.00 (n=4) |
| version | 1.00 (n=4) |

## Misses and violations

- `test` · `hybrid-rrf-rerank` · `eng-failover-before-after-004` · recall@10=0.5 · violations=none
- `test` · `hybrid-rrf-rerank` · `rel-resign-025` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf-rerank` · `oncall-night-036` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `refund-monthly-047` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf-rerank` · `log-legal-hold-138` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf-rerank` · `search-console-age-166` · recall@10=0.0 · violations=none
- `test` · `hybrid-rrf-rerank` · `obs-cause-alerts-169` · recall@10=0.0 · violations=none
- `dev` · `hybrid-rrf-rerank` · `obs-noisy-alerts-170` · recall@10=0.0 · violations=none
