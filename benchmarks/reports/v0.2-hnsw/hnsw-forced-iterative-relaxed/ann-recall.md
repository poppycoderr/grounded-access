# hnsw-forced-iterative-relaxed against exact

Recall@10: the share of exact search's top 10 that `hnsw-forced-iterative-relaxed` also returned. Distance recall: the share of `hnsw-forced-iterative-relaxed`'s top 10 at least as close as exact search's 10th result.

| Principal | Searches | Recall | Distance recall | Fewer results | Empty |
|---|---|---|---|---|---|
| alice-engineer | 128 | 0.242 | 0.242 | 0 | 0 |
| bob-support | 128 | 0.244 | 0.244 | 0 | 0 |
| carol-manager | 128 | 0.209 | 0.209 | 0 | 0 |
| dave-contractor | 128 | 0.622 | 0.622 | 0 | 0 |
| mallory-outsider | 128 | 0.863 | 0.863 | 0 | 0 |
| all | 640 | 0.436 | 0.436 | 0 | 0 |

Latency, p50 / p95 ms: `exact` 66.8 / 86.8, `hnsw-forced-iterative-relaxed` 35.6 / 56.5.

Median spread between exact search's first and 10th score: 0.0680.
