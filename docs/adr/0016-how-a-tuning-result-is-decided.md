# ADR-0016: How a tuning result is decided

- Status: Accepted
- Date: 2026-11-13

## Context

`ga-eval run` compares every pair of strategies on three metrics, each with a paired bootstrap interval. With five strategies that makes 10 pairs × 3 metrics = 30 intervals at 95%, with no correction for multiple comparisons. Even when nothing differs, one or two of the 30 are expected to exclude zero by chance. The v1 finding "BM25 beats FTS", later withdrawn, was one such case (review 2026-11-10, P1-5).

Step 3 of the v0.2 plan tunes the candidate counts, the RRF constant, the rerank depth and the heading context. Each change is reported on `test`. Every look at `test` that leads to keeping or dropping a change uses up some of the split's independence. Heading context was chosen on `dev` and kept after `test` showed no harm, so `test` has already taken part in one decision.

Dataset v5 adds 198 `test` cases that no decision has used. The review of 2026-11-10 ran them once, on a local machine, and printed aggregate numbers and a list of misses. This ADR records that look and fixes the rules before the data is used for anything else.

## Decision

- **One primary comparison per change, declared before the run.** A pull request that changes ranking, chunking or filtering names one strategy and one metric in its description before the evaluation is run. For example: "`hybrid-rrf-rerank`, MRR@10, `test` split, before against after". `ga-eval compare --primary STRATEGY METRIC` marks that comparison. Its verdict is whether its unadjusted 95% paired interval excludes zero. Only the primary comparison can support "improvement" or "regression".
- **The primary strategy is the current default.** For step 3 that is `hybrid-rrf-rerank`: a change is adopted for what it does to the configuration that ships, not for what it does to some channel. Use MRR@10 for a change in ranking order and Recall@10 for a change in the candidate set.
- **Everything else is exploratory, with Holm-adjusted verdicts.** Every other row and metric of `ga-eval compare`, and the whole strategy-pair table of `ga-eval run`, gets a two-sided bootstrap p-value from the same resamples as its interval. The p-values are Holm-adjusted across the table. A difference counts as a finding only if its adjusted p-value is below 0.05. Intervals are still printed unadjusted, and the report says so.
- **Tuning reads `dev` only.** A configuration is fixed on `dev`. `test` is read once per decision, after the configuration is fixed, and the comparison is the declared one.
- **A holdout split that v0.2 does not read.** A fifth of the `test` cases that v5 added was moved to a new `holdout` split. The draw is stratified by principal, must-abstain and authorization negative, uses seed 20261113, and leaves 39 cases (34 answerable). No published number uses any of them. `ga-eval run` reads `dev` and `test` unless `--split holdout` is given. The holdout is read once, at the v0.2 release, to confirm the final configuration. Its cases are reviewed under ADR-0015 before that read.
- **The review's look is recorded, not undone.** The local run of 2026-11-10 counts as one look at v5 `test`. No configuration was chosen on it, and none of its numbers is published.

## Why a fifth and not a quarter

v0.2 plan step 1b requires at least 200 answerable `test` cases. A quarter of the new `test` cases would leave 197. A fifth leaves 206 and puts 34 answerable cases in the holdout. That is enough to see a large regression and too few to measure a small gain. The holdout is a check that the result transfers, not a second benchmark.

## Consequences

- Published comparison tables can show fewer "better" and "worse" verdicts than before, because 30 tests at 0.05 each are now one family at 0.05.
- `ga-eval compare` without `--primary` decides nothing. Its output says that no primary comparison was declared.
- `run.json` records `p` and `p_holm` for each strategy pair and metric. `comparison.json` gains a `tests` list with each verdict's role. Earlier reports keep the verdicts they were published with, and `ga-eval compare` reproduces their rows unchanged.
- Dataset v5 has three splits: 80 `dev`, 253 `test`, 39 `holdout`. A reviewed holdout case does not enter a published number until the release reads the holdout.
