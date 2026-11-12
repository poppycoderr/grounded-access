# Retrieval evaluation — dataset v4

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `e5c3293`, 128 cases, k=10, policy abac/1, chunker markdown/4, generated 2026-11-12T14:22:33+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Measured: server commit `unknown` (0.1.0-SNAPSHOT), index `BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55`, embedding context on, chunker release 4 (180/30 words), reranker `Xenova/ms-marco-MiniLM-L-12-v2@42a4a787e30451cf9dbd09080c2a5b8dde332c1e`, server CPU `aarch64` (6 processors), corpus `93b180b2a371` (328 chunks as listed). Evaluator commit `e5c3293`, 0 warmup requests, up to 3 attempts per request, metrics version 2.

Retrieval plans: `hybrid-rrf-rerank` `748ba4f7e675effe`.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 23 | 1.000 [1.00, 1.00] | 1.000 [1.00, 1.00] | 0.928 [0.84, 1.00] | 0.946 [0.88, 1.00] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 70 | 0.971 [0.93, 1.00] | 0.986 [0.96, 1.00] | 0.971 [0.94, 1.00] | 0.963 [0.92, 0.99] | 0 |

## Hard negatives (test split)

How often a document that looks relevant but is wrong ranks above the first correct evidence.

| Strategy | Cases with hard negatives | Ranked above evidence |
|---|---|---|
| `hybrid-rrf-rerank` | 29 | 8 |

## Latency (test split)

Request latency seen by the evaluation client on `arm`, HTTP round trip included. It shows what each stage costs on this machine and is not a performance claim.

| Strategy | Requests | p50 ms | p95 ms |
|---|---|---|---|
| `hybrid-rrf-rerank` | 94 | 919 | 1126 |

## Scope (test split)

Cases that set a region or a date, or name documents that are readable but do not apply. Returning such a document is a scope failure. It is counted here and never as a security violation.

| Strategy | Scope cases | Scope failures |
|---|---|---|
| `hybrid-rrf-rerank` | 10 | 0 |

## Recall@10 by tag (test split)

| Tag | `hybrid-rrf-rerank` |
|---|---|
| abbreviation | 1.00 (n=5) |
| api | 1.00 (n=3) |
| authorization | 0.90 (n=10) |
| backup | 1.00 (n=4) |
| clearance | 0.80 (n=5) |
| cross-tenant | 1.00 (n=2) |
| department | 1.00 (n=4) |
| hard-negative | 1.00 (n=18) |
| identical-wording | 1.00 (n=5) |
| incident | 1.00 (n=1) |
| lexical | 1.00 (n=3) |
| multi-document | 1.00 (n=1) |
| multi-section | 1.00 (n=2) |
| numbers | 1.00 (n=31) |
| oncall | 1.00 (n=5) |
| paraphrase | 0.97 (n=33) |
| policy | 1.00 (n=11) |
| project | 0.50 (n=2) |
| prompt-injection | 1.00 (n=5) |
| region | 1.00 (n=4) |
| runbook | 1.00 (n=6) |
| sales | 1.00 (n=5) |
| scope | 1.00 (n=8) |
| security | 1.00 (n=5) |
| single-hop | 1.00 (n=8) |
| support | 1.00 (n=5) |
| validity | 1.00 (n=4) |
| version | 1.00 (n=2) |

## Keyword rankings

Not checked: the run has no keyword strategy.

## Misses and violations

- `test` · `hybrid-rrf-rerank` · `authz-borealis-gates-075` · recall@10=0.0 · violations=none
