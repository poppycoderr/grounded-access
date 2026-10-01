# M1b: does hybrid beat the best single channel?

Status: result of the committed run [`benchmarks/reports/m1b-hybrid/`](../../benchmarks/reports/m1b-hybrid/). Every number below comes from that run: `test` split, 47 answerable cases, produced by the CI runner (Linux x86_64). The per-case figures are derived from its `cases.jsonl`.

## Verdict

**No.** On this dataset `hybrid-rrf` does not beat `dense-only`.

| Comparison (second minus first) | ΔRecall@10 | ΔMRR@10 | ΔnDCG@10 |
|---|---|---|---|
| `dense-only` → `hybrid-rrf` | +0.000 [−0.06, +0.06] | −0.050 [−0.12, +0.03] | −0.040 [−0.10, +0.02] |
| `sparse-only` → `hybrid-rrf` | +0.043 [+0.00, +0.11] | +0.194 [+0.12, +0.27] | +0.154 [+0.10, +0.22] |
| `hybrid-rrf` → `bm25-reference` | −0.053 [−0.13, +0.00] | −0.094 [−0.18, −0.01] | −0.086 [−0.16, −0.02] |

- Against dense, every interval includes zero: there is no detectable difference, and the point estimates for MRR@10 and nDCG@10 favour dense.
- Against sparse alone, hybrid is clearly better on MRR@10 and nDCG@10.
- Hybrid is clearly better than the BM25 reference, as dense already was.
- Security violations are zero for every strategy.

## Where the difference comes from

Comparing the rank of the first relevant result, hybrid against dense: 4 cases better, 10 worse, 33 equal.

| Pattern | Cases | What happens |
|---|---|---|
| Sparse channel does not return the evidence | 3 worse | The question is a paraphrase with almost no shared words. The correct chunk scores from the dense channel only, while distractors that both channels return score twice and overtake it: dense rank 1 becomes hybrid rank 3 and 5, and dense rank 8 falls out of the top 10. |
| Sparse channel ranks the evidence lower than dense | 7 worse | The evidence is pulled down by one or two places (for example dense rank 1, sparse rank 5, hybrid rank 2). |
| Dense misses or ranks low, sparse ranks high | 4 better | Hybrid recovers the case: one that dense does not return in the top 10 comes back at rank 8, and one moves from dense rank 7 to rank 1. |

Two aggregate effects follow. Recall@5 rises from 0.926 to 0.957, because hybrid rescues the cases dense gets badly wrong. MRR@10 falls from 0.860 to 0.810, because hybrid more often moves a correct first result down a place or two.

## Reading

Equal-weight rank fusion gives the weaker channel the same vote as the stronger one. The M1a baseline already showed PostgreSQL FTS measurably below BM25 on this dataset, and 30 of the 63 answerable cases are deliberate paraphrases that keyword search cannot match. With that partner, fusion trades precision at the top for robustness further down.

This is a statement about this configuration on this dataset, not about hybrid retrieval in general. The dataset is small and fictional, and the plan uses the common defaults (50 candidates per channel, RRF constant 60) without tuning.

## What was not done, and what comes next

- Nothing was tuned on the `test` split, and the configuration was fixed before the run.
- Channel weights in the fusion and a stronger sparse ranker are the obvious experiments. Either would be tuned on the `dev` split only and then reported on `test` once, with this run as the comparison.
- The cross-encoder reranker (M3) operates on the fused candidates. Its report compares against `hybrid-rrf` and `dense-only`, so a reranker gain is not credited to fusion.
- `dense-only` stays the default strategy of the demo commands.
