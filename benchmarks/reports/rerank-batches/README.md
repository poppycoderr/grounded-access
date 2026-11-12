# Reranking in sorted batches — datasets v4 and v5

Local runs on an Apple Silicon laptop (`arm` in the run files) with the stack in Docker (6 CPUs) and the client on the same machine,
2026-11-12. The machine ran other work: load averages of 4 to 10 were recorded during the runs. Absolute times describe this machine;
only the before-and-after pairs, run minutes apart on the same stack, are compared.

- **Before** is the model service of `main` at `e5c3293`: the whole candidate pool in one fastembed call.
- **After** is the same commit plus this change: passages sorted by their token count with the query and scored in batches of four
  ([ADR-0010, amendment](../../../docs/adr/0010-deadlines-and-admission-for-model-calls.md#amendment-2026-11-12-rerank-batches)).

Only the model service was rebuilt between a before and an after run; the database, the control plane and the loaded corpus were the same.

## Why

fastembed pads every passage of a call to the longest one. One long candidate (a code block or a JSON line near the 384-token chunk
budget) therefore made a pool of short candidates cost as much as a pool of long ones. The review of 2026-11-10 measured it on dataset
v5: 20 candidates took 5.44 s with one 141-word YAML block among them and 0.76 s without it, against a 3 s rerank timeout.

## v4: rankings unchanged

`hybrid-rrf-rerank` on all 128 cases of dataset v4 ([before](./v4-before/report.md), [after](./v4-after/report.md)). The evaluator ran
from `e5c3293`.

| | Cases with a different top 10 | Degraded attempts | p50 ms | p95 ms |
|---|---|---|---|---|
| before | — | 0 | 915 | 1,085 |
| after | 0 of 128 | 0 | 647 | 862 |

Every case returned the same ten chunks in the same order, so every metric is identical. Batching does not change a passage's score,
because padding is masked; the real-model test `test_batched_scores_match_scoring_each_passage_alone` checks that to 1e-4.

## v5: degradation and latency

Dataset v5 is on the unmerged branch `feat/dataset-v5`; the evaluator ran from its commit `cac172a`. These are latency measurements of a
dataset whose labels have not been reviewed; no retrieval-quality number from them is used anywhere.

150 serial `hybrid-rrf-rerank` searches, each case as its own principal (`ga-eval load-test --workers 1 --requests 150
--allow-degraded`; [before](./v5-load-before/report.md), [after](./v5-load-after/report.md)):

| | Degraded (`rerank_unavailable`) | p50 ms | p95 ms | max ms | Unauthorized results |
|---|---|---|---|---|---|
| before | 78 of 150 (52%) | 3,034 | 3,110 | 3,184 | 0 |
| after | 0 of 150 | 1,044 | 2,037 | 2,716 | 0 |

The full benchmark of `hybrid-rrf-rerank` on v5 is still invalid on this machine, but much later:

- **Before** ([partial run](./v5-before/report.md)): its first case was degraded three times in a row.
- **After** ([partial run](./v5-after/report.md)): 171 cases ran with no degraded attempt (p95 2,110 ms). Case `obs-sample-rule-172` was then
  degraded three times. Its 20 candidates are mostly long: 3,758 tokens together with the query, five of them over 350.

[`pool-timing.py`](./pool-timing.py) on that pool:

| Where | One batch | Sorted batches of four | One at a time |
|---|---|---|---|
| natively on the laptop | 1.15 s | 0.62 s | 0.57 s |
| inside the model-service container | 5.56 s | 3.03 s | 2.66 s |

[`batch-size.py`](./batch-size.py) compares batch sizes on two synthetic pools ([output](./batch-size.txt)); it is why the batch size is four.

Sorting removes the padding cost: the pool now costs what its tokens cost, about half of before. On this laptop's Docker, which runs the
model about five times slower than natively, a pool of 3,758 tokens still reaches the 3 s limit. CI's x86 runner ran the full v5
benchmark, rerank included, before this change (smoke job of poppycoderr/grounded-access#94). What bounds the total token cost of a call is left open in the ADR
amendment.
