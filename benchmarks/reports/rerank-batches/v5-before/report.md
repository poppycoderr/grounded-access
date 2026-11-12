# Retrieval evaluation — dataset v5

Demo benchmark on a small fictional corpus; not representative of production quality. Commit `cac172a`, 372 cases, k=10, policy abac/1, chunker markdown/4, generated 2026-11-12T14:41:47+00:00. Intervals are 95% percentile bootstraps over cases (10,000 samples, seed 20260921).

Measured: server commit `unknown` (0.1.0-SNAPSHOT), index `BAAI/bge-small-en-v1.5@aa8f8b060edb00e03bfdd08813a2949946c8ba55`, embedding context on, chunker release 4 (180/30 words), reranker `Xenova/ms-marco-MiniLM-L-12-v2@42a4a787e30451cf9dbd09080c2a5b8dde332c1e`, server CPU `aarch64` (6 processors), corpus `c783e312846a` (2965 chunks as listed). Evaluator commit `cac172a`, 0 warmup requests, up to 3 attempts per request, metrics version 2.

Retrieval plans: `hybrid-rrf-rerank` `748ba4f7e675effe`.

2 request(s) were answered degraded and sent again; only complete answers are scored. This happens when a model call exceeds its timeout on a busy machine.

**This run is invalid and incomplete:** case hr-volunteer-eu-001 · hybrid-rrf-rerank was answered degraded (rerank_unavailable) 3 times in a row; the run is invalid. It is kept as evidence, not as a result.

## Tuning split (dev) — not a result

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 0 | 0.000 [0.00, 0.00] | 0.000 [0.00, 0.00] | 0.000 [0.00, 0.00] | 0.000 [0.00, 0.00] | 0 |

## Results (test split)

| Strategy | Cases | Recall@5 | Recall@10 | MRR@10 | EC@10 | Violations |
|---|---|---|---|---|---|---|
| `hybrid-rrf-rerank` | 0 | 0.000 [0.00, 0.00] | 0.000 [0.00, 0.00] | 0.000 [0.00, 0.00] | 0.000 [0.00, 0.00] | 0 |

## Recall@10 by tag (test split)

| Tag | `hybrid-rrf-rerank` |
|---|---|

## Misses and violations

None.
