# Load smoke test — dataset v4

300 searches from 1 concurrent clients as 5 principals, k=10, commit `718f16f`, generated 2026-11-06T14:15:23+00:00 on `arm`. 4.47 s in total, 67.2 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 100 | 0 | 0 | 0 | 5.0 | 7.7 | 44.5 |
| `dense-only` | 100 | 0 | 0 | 0 | 17.8 | 26.0 | 33.6 |
| `hybrid-rrf` | 100 | 0 | 0 | 0 | 19.4 | 29.2 | 41.8 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
