# Load smoke test — dataset v4

300 searches from 1 concurrent clients as 5 principals, k=10, commit `718f16f`, generated 2026-11-06T14:38:32+00:00 on `arm`. 38.46 s in total, 7.8 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 100 | 0 | 0 | 0 | 115.0 | 301.3 | 386.2 |
| `dense-only` | 100 | 0 | 0 | 0 | 68.7 | 87.4 | 150.4 |
| `hybrid-rrf` | 100 | 0 | 0 | 0 | 179.5 | 385.4 | 480.3 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
