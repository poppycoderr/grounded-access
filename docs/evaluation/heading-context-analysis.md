# Heading context: what the test split says

After v0.1, each chunk is embedded and keyword-indexed together with its heading path (`markdown/3`). This page reads the result. The numbers come from two CI runs on dataset v4: [`v0.1/`](../../benchmarks/reports/v0.1/) before the change and [`heading-context/`](../../benchmarks/reports/heading-context/) after it, compared case by case with `ga-eval compare` ([`comparison.md`](../../benchmarks/reports/heading-context/comparison.md)).

## Result (test split, 70 answerable cases)

| Strategy | Recall@10 | ΔRecall@10 | MRR@10 | ΔMRR@10 | Cases better / worse |
|---|---|---|---|---|---|
| `sparse-only` | 0.929 → 0.943 | +0.014 [−0.04, +0.07] | 0.690 → 0.740 | +0.050 [−0.01, +0.11] | 15 / 7 |
| `dense-only` | 0.971 → 0.979 | +0.007 [−0.02, +0.04] | 0.865 → 0.895 | +0.030 [−0.03, +0.09] | 10 / 4 |
| `hybrid-rrf` | 0.971 → 0.986 | +0.014 [+0.00, +0.04] | 0.825 → 0.867 | +0.042 [−0.01, +0.10] | 8 / 5 |
| `hybrid-rrf-rerank` | 0.971 → 0.986 | +0.014 [+0.00, +0.04] | 0.957 → 0.971 | +0.014 [+0.00, +0.04] | 1 / 0 |

**No difference is detectable.** Every point estimate moved up and every interval includes zero. The change was chosen on the `dev` split, where `hybrid-rrf` gained +0.069 [+0.01, +0.15]; on the test split the same strategy gains +0.042 with an interval that reaches below zero. Security violations and scope failures are zero in both runs.

The two runs were made on different CI runners (Intel Xeon and AMD EPYC). Near-tied dense candidates can swap between CPUs, which moves MRR in the third decimal and is far below the differences in this table.

## What it fixed

`pl-return-022`, "Can I work part time after coming back from baby leave?", was case 1 of the v0.1 report: the evidence was at fused rank 23 and never reached the reranker. Its heading is "Returning to work". With the heading indexed, dense ranks the passage first instead of ninth, and `hybrid-rrf` and `hybrid-rrf-rerank` go from zero to a correct first result. This is the one case that changes for the reranked strategy.

`hr-travel-meal-010`, case 6, also improves: the keyword channel now finds the evidence through the heading "Daily allowance" and ranks it first, so fusion no longer demotes the dense channel's correct result (MRR 0.2 → 1.0 for `hybrid-rrf`).

## What it did not fix

**The case that motivated the change is still missed by every strategy.** `authz-borealis-gates-075` asks what has to be true before "the usage-based pricing engine" can go live. The heading path adds "Project Borealis: Launch Runbook > Launch gates" to the passage. It does not add "pricing engine": that phrase is only in the document's introduction. The v0.1 report named both the introduction and the heading as what the passage depends on; the heading alone was not enough. Fixing it would need context from elsewhere in the document, for example a one-sentence description of the document in front of every chunk.

## What it made worse

`backup-rpo-037`, "What is the RPO for the billing database?", went from first to sixth in three strategies. The evidence is in the backup standard, under "Objectives". Above it now are passages from "Runbook: Billing Database Failover": every chunk of that runbook now carries the words "Billing Database" from its title, whatever the chunk is about. The reranker still puts the right passage first.

This is the cost of the approach. A document title is added to all of the document's chunks, so a question that matches the title lifts all of them, and passages inside one document become more alike. Four to seven cases per strategy rank worse for reasons of this kind.

## Reading

- Heading context is the default because it was chosen by the rule the project sets itself: pick on `dev`, then measure on `test`. The test split does not show harm, and it does not show a gain. Anyone who reads "+0.04 MRR" as an improvement is reading more than the data says.
- With 70 cases, a real difference of a few points cannot be detected. A larger dataset is the precondition for deciding questions like this one, more than another indexing variant is.
- The switches (`GA_EMBEDDING_CONTEXT`, `GA_SPARSE_CONTEXT`) stay, so the comparison can be repeated and the decision reversed.
- A variant that adds only the nearest heading, not the document title, might avoid the `backup-rpo-037` kind of regression. It was not tried: the idea comes from reading test cases, and trying it would be tuning on the test split unless it is first chosen on `dev`.
