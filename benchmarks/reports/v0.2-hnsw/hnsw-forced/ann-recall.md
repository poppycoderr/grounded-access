# hnsw-forced against exact

Recall@10: the share of exact search's top 10 that `hnsw-forced` also returned. Distance recall: the share of `hnsw-forced`'s top 10 at least as close as exact search's 10th result.

| Principal | Searches | Recall | Distance recall | Fewer results | Empty |
|---|---|---|---|---|---|
| alice-engineer | 128 | 0.191 | 0.191 | 0 | 0 |
| bob-support | 128 | 0.191 | 0.191 | 0 | 0 |
| carol-manager | 128 | 0.182 | 0.182 | 0 | 0 |
| dave-contractor | 128 | 0.239 | 0.239 | 127 | 3 |
| mallory-outsider | 128 | 0.048 | 0.048 | 128 | 82 |
| all | 640 | 0.170 | 0.170 | 255 | 85 |

Latency, p50 / p95 ms: `exact` 66.8 / 86.8, `hnsw-forced` 26.2 / 40.4.

Median spread between exact search's first and 10th score: 0.0680.
