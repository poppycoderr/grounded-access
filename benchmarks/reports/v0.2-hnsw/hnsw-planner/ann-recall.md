# hnsw-planner against exact

Recall@10: the share of exact search's top 10 that `hnsw-planner` also returned. Distance recall: the share of `hnsw-planner`'s top 10 at least as close as exact search's 10th result.

| Principal | Searches | Recall | Distance recall | Fewer results | Empty |
|---|---|---|---|---|---|
| alice-engineer | 128 | 1.000 | 1.000 | 0 | 0 |
| bob-support | 128 | 1.000 | 1.000 | 0 | 0 |
| carol-manager | 128 | 1.000 | 1.000 | 0 | 0 |
| dave-contractor | 128 | 1.000 | 1.000 | 0 | 0 |
| mallory-outsider | 128 | 1.000 | 1.000 | 0 | 0 |
| all | 640 | 1.000 | 1.000 | 0 | 0 |

Latency, p50 / p95 ms: `exact` 66.8 / 86.8, `hnsw-planner` 69.9 / 119.3.

Median spread between exact search's first and 10th score: 0.0680.
