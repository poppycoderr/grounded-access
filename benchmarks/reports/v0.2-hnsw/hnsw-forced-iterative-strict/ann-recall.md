# hnsw-forced-iterative-strict against exact

Recall@10: the share of exact search's top 10 that `hnsw-forced-iterative-strict` also returned. Distance recall: the share of `hnsw-forced-iterative-strict`'s top 10 at least as close as exact search's 10th result.

| Principal | Searches | Recall | Distance recall | Fewer results | Empty |
|---|---|---|---|---|---|
| alice-engineer | 128 | 0.191 | 0.191 | 0 | 0 |
| bob-support | 128 | 0.191 | 0.191 | 0 | 0 |
| carol-manager | 128 | 0.182 | 0.182 | 0 | 0 |
| dave-contractor | 128 | 0.338 | 0.338 | 0 | 0 |
| mallory-outsider | 128 | 0.432 | 0.441 | 0 | 0 |
| all | 640 | 0.267 | 0.268 | 0 | 0 |

Latency, p50 / p95 ms: `exact` 66.8 / 86.8, `hnsw-forced-iterative-strict` 39.9 / 71.2.

Median spread between exact search's first and 10th score: 0.0680.
