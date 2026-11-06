# Scale run

Rendered from the files of this directory by `ga-eval scale-report`. Client commit `718f16f`, client CPU `arm`. Every result was checked against the visible set of the principal that asked, labelled or generated.

## Latency per request, as the client measured it

| Chunks | Clients | Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| 135 | 1 | `sparse-only` | 100 | 0 | 0 | 0 | 5.0 | 7.7 | 44.5 |
| 135 | 1 | `dense-only` | 100 | 0 | 0 | 0 | 17.8 | 26.0 | 33.6 |
| 135 | 1 | `hybrid-rrf` | 100 | 0 | 0 | 0 | 19.4 | 29.2 | 41.8 |
| 135 | 16 | `sparse-only` | 200 | 0 | 0 | 0 | 10.7 | 33.7 | 57.3 |
| 135 | 16 | `dense-only` | 200 | 0 | 0 | 0 | 102.9 | 162.4 | 196.8 |
| 135 | 16 | `hybrid-rrf` | 200 | 0 | 0 | 0 | 105.3 | 155.8 | 192.2 |
| 3,263 | 1 | `sparse-only` | 100 | 0 | 0 | 0 | 11.6 | 34.5 | 44.1 |
| 3,263 | 1 | `dense-only` | 100 | 0 | 0 | 0 | 22.1 | 33.6 | 49.6 |
| 3,263 | 1 | `hybrid-rrf` | 100 | 0 | 0 | 0 | 31.7 | 56.7 | 101.7 |
| 3,263 | 16 | `sparse-only` | 200 | 0 | 0 | 0 | 51.3 | 177.8 | 296.2 |
| 3,263 | 16 | `dense-only` | 200 | 0 | 0 | 0 | 134.2 | 227.8 | 348.6 |
| 3,263 | 16 | `hybrid-rrf` | 200 | 0 | 0 | 0 | 182.2 | 360.4 | 535.6 |
| 33,283 | 1 | `sparse-only` | 100 | 0 | 0 | 0 | 115.0 | 301.3 | 386.2 |
| 33,283 | 1 | `dense-only` | 100 | 0 | 0 | 0 | 68.7 | 87.4 | 150.4 |
| 33,283 | 1 | `hybrid-rrf` | 100 | 0 | 0 | 0 | 179.5 | 385.4 | 480.3 |
| 33,283 | 16 | `sparse-only` | 200 | 0 | 0 | 0 | 417.8 | 1092.0 | 1609.4 |
| 33,283 | 16 | `dense-only` | 200 | 0 | 0 | 0 | 356.5 | 627.2 | 1088.7 |
| 33,283 | 16 | `hybrid-rrf` | 200 | 0 | 0 | 0 | 767.0 | 1456.4 | 1782.7 |

## Stage timings from the execution records

Milliseconds, p50 / p95. The sparse stage is its SQL statement. The dense stage includes the query embedding, which does not depend on the corpus size.

| Chunks | Clients | Strategy | Sparse p50 / p95 | Dense p50 / p95 |
|---|---|---|---|---|
| 135 | 1 | `dense-only` | 0 / 0 | 12 / 18 |
| 135 | 1 | `hybrid-rrf` | 1 / 1 | 12 / 20.05 |
| 135 | 1 | `sparse-only` | 1 / 1 | 0 / 0 |
| 135 | 16 | `dense-only` | 0 / 0 | 90 / 140.05 |
| 135 | 16 | `hybrid-rrf` | 1 / 6 | 90 / 139 |
| 135 | 16 | `sparse-only` | 1 / 5 | 0 / 0 |
| 3,263 | 1 | `dense-only` | 0 / 0 | 18 / 26 |
| 3,263 | 1 | `hybrid-rrf` | 7 / 26.1 | 18 / 30.15 |
| 3,263 | 1 | `sparse-only` | 8 / 25.15 | 0 / 0 |
| 3,263 | 16 | `dense-only` | 0 / 0 | 113.5 / 203.55 |
| 3,263 | 16 | `hybrid-rrf` | 35.5 / 158.15 | 117 / 209 |
| 3,263 | 16 | `sparse-only` | 36 / 147.35 | 0 / 0 |
| 33,283 | 1 | `dense-only` | 0 / 0 | 64 / 82.05 |
| 33,283 | 1 | `hybrid-rrf` | 112 / 285.6 | 64 / 80.15 |
| 33,283 | 1 | `sparse-only` | 109 / 282.55 | 0 / 0 |
| 33,283 | 16 | `dense-only` | 0 / 0 | 330.5 / 566.5 |
| 33,283 | 16 | `hybrid-rrf` | 378.5 / 1041.1 | 323.5 / 562.35 |
| 33,283 | 16 | `sparse-only` | 384.5 / 1008.7 | 0 / 0 |

## Throughput and audit growth

| Chunks | Clients | Requests per second | Audit rows per 1,000 searches | Audit KiB per 1,000 searches |
|---|---|---|---|---|
| 135 | 1 | 67.2 | 2000 | 1573 |
| 135 | 16 | 209.1 | 2000 | 1480 |
| 3,263 | 1 | 43.0 | 2000 | 1440 |
| 3,263 | 16 | 118.0 | 2000 | 1360 |
| 33,283 | 1 | 7.8 | 2000 | 800 |
| 33,283 | 16 | 29.4 | 2000 | 1467 |
