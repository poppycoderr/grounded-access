# Load smoke test — dataset v4

600 searches from 16 concurrent clients as 5 principals, k=10, commit `718f16f`, generated 2026-11-06T14:15:27+00:00 on `arm`. 2.87 s in total, 209.1 requests per second.

This is a smoke test on a tiny corpus, with the client on the same machine as the stack. It checks that concurrent requests stay correct and shows which stage saturates first. It is not a performance claim.

| Strategy | Requests | Not 200 | Degraded | Violations | p50 ms | p95 ms | max ms |
|---|---|---|---|---|---|---|---|
| `sparse-only` | 200 | 0 | 0 | 0 | 10.7 | 33.7 | 57.3 |
| `dense-only` | 200 | 0 | 0 | 0 | 102.9 | 162.4 | 196.8 |
| `hybrid-rrf` | 200 | 0 | 0 | 0 | 105.3 | 155.8 | 192.2 |

Every result was checked against the visible set of the principal that asked: 0 unauthorized results. Trace ids shared by two requests: 0.
