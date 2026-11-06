# Scale run

Rendered from the files of this directory by `ga-eval scale-report`. Client commit `5599870`, client CPU `arm`. Every result was checked against the visible set of the principal that asked, labelled or generated.

## Latency per request, as the client measured it

| Chunks | Clients | Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| 135 | 1 | `sparse-only` | 100 | 0 | 0 | 0 | 4.9 | 7.4 | 45.4 |
| 135 | 1 | `dense-only` | 100 | 0 | 0 | 0 | 21.3 | 27.5 | 46.1 |
| 135 | 1 | `hybrid-rrf` | 100 | 0 | 0 | 0 | 22.5 | 29.1 | 33.9 |
| 135 | 16 | `sparse-only` | 200 | 0 | 0 | 0 | 11.8 | 36.0 | 79.3 |
| 135 | 16 | `dense-only` | 200 | 0 | 0 | 0 | 136.0 | 204.1 | 261.5 |
| 135 | 16 | `hybrid-rrf` | 200 | 0 | 0 | 0 | 144.3 | 223.5 | 245.9 |
| 3,263 | 1 | `sparse-only` | 100 | 0 | 0 | 0 | 11.3 | 29.4 | 41.6 |
| 3,263 | 1 | `dense-only` | 100 | 0 | 0 | 0 | 25.7 | 113.2 | 135.1 |
| 3,263 | 1 | `hybrid-rrf` | 100 | 0 | 0 | 0 | 33.2 | 128.5 | 151.4 |
| 3,263 | 16 | `sparse-only` | 200 | 0 | 0 | 0 | 108.1 | 326.7 | 499.1 |
| 3,263 | 16 | `dense-only` | 200 | 0 | 0 | 0 | 363.4 | 623.1 | 984.7 |
| 3,263 | 16 | `hybrid-rrf` | 200 | 0 | 0 | 0 | 507.9 | 831.1 | 1001.0 |
| 33,283 | 1 | `sparse-only` | 100 | 0 | 0 | 0 | 122.3 | 293.7 | 409.6 |
| 33,283 | 1 | `dense-only` | 100 | 0 | 0 | 0 | 793.9 | 981.7 | 990.3 |
| 33,283 | 1 | `hybrid-rrf` | 100 | 0 | 0 | 0 | 918.2 | 1228.8 | 1375.8 |
| 33,283 | 16 | `sparse-only` | 200 | 0 | 0 | 0 | 679.5 | 1717.7 | 2465.2 |
| 33,283 | 16 | `dense-only` | 200 | 83 | 0 | 0 | 2119.4 | 3244.4 | 4260.8 |
| 33,283 | 16 | `hybrid-rrf` | 200 | 86 | 0 | 0 | 2813.9 | 4494.4 | 5306.8 |

## Stage timings from the execution records

Milliseconds, p50 / p95. The sparse stage is its SQL statement. The dense stage includes the query embedding, which does not depend on the corpus size.

| Chunks | Clients | Strategy | Sparse p50 / p95 | Dense p50 / p95 |
|---|---|---|---|---|
| 135 | 1 | `dense-only` | 0 / 0 | 16 / 22 |
| 135 | 1 | `hybrid-rrf` | 0.5 / 1 | 16 / 22 |
| 135 | 1 | `sparse-only` | 1 / 1 | 0 / 0 |
| 135 | 16 | `dense-only` | 0 / 0 | 123 / 185 |
| 135 | 16 | `hybrid-rrf` | 1 / 8 | 129 / 199.4 |
| 135 | 16 | `sparse-only` | 1 / 9 | 0 / 0 |
| 3,263 | 1 | `dense-only` | 0 / 0 | 21 / 108.05 |
| 3,263 | 1 | `hybrid-rrf` | 6.5 / 25 | 21 / 108.05 |
| 3,263 | 1 | `sparse-only` | 7 / 22.15 | 0 / 0 |
| 3,263 | 16 | `dense-only` | 0 / 0 | 354 / 605.35 |
| 3,263 | 16 | `hybrid-rrf` | 97.5 / 274.25 | 342.5 / 628.05 |
| 3,263 | 16 | `sparse-only` | 95.5 / 270.6 | 0 / 0 |
| 33,283 | 1 | `dense-only` | 0 / 0 | 789 / 976.05 |
| 33,283 | 1 | `hybrid-rrf` | 117 / 304.2 | 790.5 / 976.05 |
| 33,283 | 1 | `sparse-only` | 116.5 / 287.1 | 0 / 0 |
| 33,283 | 16 | `dense-only` | 0 / 0 | 514 / 2696.4 |
| 33,283 | 16 | `hybrid-rrf` | 477.5 / 1583.75 | 516 / 2744.15 |
| 33,283 | 16 | `sparse-only` | 633 / 1507.45 | 0 / 0 |

## Throughput and audit growth

| Chunks | Clients | Requests per second | Audit rows per 1,000 searches | Audit KiB per 1,000 searches |
|---|---|---|---|---|
| 135 | 1 | 60.5 | 2000 | 1627 |
| 135 | 16 | 155.1 | 2000 | 1560 |
| 3,263 | 1 | 24.3 | 2000 | 1760 |
| 3,263 | 16 | 50.3 | 2000 | 1320 |
| 33,283 | 1 | 1.8 | 2000 | 1573 |
| 33,283 | 16 | 9.5 | 1437 | 1053 |
