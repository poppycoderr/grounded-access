# Baseline on dataset v5

The CI `smoke` job of commit `d0c012f` on `main` (GitHub-hosted runner, AMD EPYC 9V74, 4 processors), 2026-11-13, unchanged from its `evaluation-results` artifact. It is the first published run on dataset v5 and the exit condition of step 1 of the [v0.2 plan](../../../docs/project/v0.2-plan.md): every strategy, manifest version 2, no drift.

- **Gates.** Status `complete`, 0 security violations and 0 scope failures over 333 cases, 67 of them authorization negatives; no repeated degraded requests; the keyword ranking check passed.
- **Which cases count.** Under [ADR-0015](../../../docs/adr/0015-where-evaluation-labels-come-from.md), summaries and paired comparisons use only the 128 cases a person checked, which are the cases of v4: 70 answerable `test` cases. The 206 answerable `test` cases including unreviewed ones appear in a table marked as not a result. The 39 `holdout` cases were not read ([ADR-0016](../../../docs/adr/0016-how-a-tuning-result-is-decided.md)).
- **Verdicts.** The strategy-pair table is exploratory: verdicts use bootstrap p-values Holm-adjusted across its 30 comparisons, and intervals are printed unadjusted. No primary comparison applies, because this run changes nothing; it is the reference later changes compare against.

## What moved against v4

The 70 reviewed cases are the same as in [`metrics-v2`](../metrics-v2/README.md), but each principal now has up to 300 documents to rank instead of 35, so the two runs are not comparable case by case (`ga-eval compare` refuses them: different datasets). Read side by side:

| Strategy | MRR@10 on v4 (`metrics-v2`) | MRR@10 on v5 (this run) |
|---|---|---|
| `sparse-only` | 0.740 | 0.437 |
| `dense-only` | 0.895 | 0.804 |
| `hybrid-rrf` | 0.867 | 0.707 |
| `hybrid-rrf-rerank` | 0.971 | 0.826 |
| `bm25-reference` | 0.772 | 0.625 |

- **FTS loses most.** The gap between BM25 and FTS, not detectable on v2–v4, is back: MRR@10 +0.19 [+0.11, +0.27], Holm-adjusted p < 0.05. This is the trigger for the open question on the keyword channel (ADR-0002).
- **Fusion pays for the weak channel.** `hybrid-rrf` is below dense: MRR@10 −0.10 [−0.18, −0.02] unadjusted, no detectable difference after the Holm adjustment.
- **Reranking no longer beats dense.** +0.02 [−0.04, +0.08] against dense, where v4 showed +0.08 [+0.03, +0.13]. It still repairs fusion: +0.12 [+0.05, +0.19] against `hybrid-rrf`.
- **Latency.** At the median, 21 ms for `sparse-only`, 37 ms for `dense-only`, 47 ms for `hybrid-rrf` and 608 ms for `hybrid-rrf-rerank`, on the reviewed `test` requests of this runner.

These are leads for step 3, not decisions: a change still declares its primary comparison before it is evaluated.
